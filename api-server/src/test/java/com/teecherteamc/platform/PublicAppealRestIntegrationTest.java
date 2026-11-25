package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.teecherteamc.platform.appeal.application.AppealLinks;
import com.teecherteamc.platform.appeal.application.AppealTokens;
import com.teecherteamc.platform.verdict.domain.VerdictEvidence;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** /api/public/appeals/{token}. 로그인 없이 서명된 토큰만으로 조회·제출한다. */
@SuppressWarnings("unchecked")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "platform.appeal.secret=0123456789abcdef0123456789abcdef",
        "platform.appeal.base-url=http://console.test"
})
class PublicAppealRestIntegrationTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AppealLinks appealLinks;

    private UUID seedAgent() {
        UUID deviceId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO devices (device_id, hostname, os_platform) VALUES (?, 'APPEAL-REST-PC', 'WINDOWS')",
                deviceId);
        UUID agentId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO agents (agent_id, device_id, agent_version, enrollment_token_hash)
                VALUES (?, ?, '0.0.1', ?)
                """, agentId, deviceId, "hash-" + agentId);
        return agentId;
    }

    private static String sha() {
        return UUID.randomUUID().toString().replace("-", "").repeat(2);
    }

    private static VerdictEvidence evidence() {
        return VerdictEvidence.similarity(12, sha(), "MalwareBazaar: AgentTesla");
    }

    private String freshToken() {
        return appealLinks.tokens().issue(UUID.randomUUID(), sha(), seedAgent(), evidence());
    }

    private static String url(String token) {
        return "/api/public/appeals/" + token;
    }

    private ResponseEntity<String> post(String token, String reason) {
        return restTemplate.postForEntity(url(token), Map.of("reason", reason), String.class);
    }

    @Test
    void get_with_bad_token_is_404() {
        ResponseEntity<Map> response = restTemplate.getForEntity(url("not-a-token"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void get_expired_token_is_404() {
        AppealTokens past = new AppealTokens(SECRET, Duration.ofDays(7),
                Clock.fixed(Instant.now().minus(Duration.ofDays(30)), ZoneOffset.UTC), JsonMapper.builder().build());
        String token = past.issue(UUID.randomUUID(), sha(), seedAgent(), evidence());

        assertThat(restTemplate.getForEntity(url(token), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void post_then_get_shows_pending() {
        String token = freshToken();

        ResponseEntity<Map> created = restTemplate.postForEntity(url(token), Map.of("reason", "업무용 파일입니다"), Map.class);
        ResponseEntity<Map> fetched = restTemplate.getForEntity(url(token), Map.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> appeal = (Map<String, Object>) fetched.getBody().get("appeal");
        assertThat(appeal.get("status")).isEqualTo("PENDING");
        assertThat(appeal.get("reason")).isEqualTo("업무용 파일입니다");
    }

    @Test
    void second_post_is_409() {
        String token = freshToken();

        assertThat(post(token, "첫 번째").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(post(token, "두 번째").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void blank_reason_is_400() {
        assertThat(post(freshToken(), "   ").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void reason_over_1000_is_400() {
        assertThat(post(freshToken(), "a".repeat(1001)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void reason_of_1000_emoji_is_201() {
        assertThat(post(freshToken(), "😀".repeat(1000)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void reason_with_nul_is_400() {
        assertThat(post(freshToken(), "abc\u0000def").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void response_has_no_url_host_or_agent() {
        UUID agentId = seedAgent();
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO download_events
                    (event_id, agent_id, request_host, url, filename, pipeline_status, decision, decision_source,
                     held_at, decided_at)
                VALUES (?, ?, 'bad.example', 'https://bad.example/f.exe', 'f.exe', 'COMPLETED', 'BLOCKED', 'SIMILARITY',
                        '2020-03-01T10:12:03Z', '2020-03-01T10:12:03Z')
                """, eventId, agentId);
        String token = appealLinks.tokens().issue(eventId, sha(), agentId, evidence());

        post(token, "사유");
        String body = restTemplate.getForEntity(url(token), String.class).getBody();

        assertThat(body).contains("f.exe");
        for (String key : new String[] {"url", "requestHost", "agentId", "hostname", "matchedSampleSha256"}) {
            assertThat(body).doesNotContain("\"" + key + "\"");
        }
        assertThat(body).doesNotContain("bad.example").doesNotContain("APPEAL-REST-PC").doesNotContain(agentId.toString());
    }

    @Test
    void admin_api_still_requires_login() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/admin/whitelist", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
