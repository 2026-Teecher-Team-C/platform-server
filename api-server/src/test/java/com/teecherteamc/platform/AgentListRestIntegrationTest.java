package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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

/**
 * GET /api/admin/agents. 로그인이 필요한 경로라서 AdminApiClient로 로그인해서 요청한다.
 * 같은 DB를 다른 테스트도 쓰므로, 테스트마다 호스트 이름 앞부분(prefix)을 따로 정하고
 * 요청도 hostname=prefix로 거른다. 그러면 다른 테스트의 행이 섞이지 않는다.
 */
@SuppressWarnings("unchecked")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfiguration.class, AdminApiClient.class})
class AgentListRestIntegrationTest {

    private static final String URL = "/api/admin/agents";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AdminApiClient adminApi;

    // 이 테스트만의 호스트 이름 앞부분. 소문자로 만든다.
    private static String newPrefix() {
        return "agt" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static Class<Map<String, Object>> mapType() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }

    private ResponseEntity<Map<String, Object>> get(String query) {
        return adminApi.getForEntity(URL + "?" + query, mapType());
    }

    private static List<Map<String, Object>> content(ResponseEntity<Map<String, Object>> response) {
        return (List<Map<String, Object>>) response.getBody().get("content");
    }

    /** devices 한 행과 agents 한 행을 넣고 agent_id를 돌려준다. lastHeartbeatAt이 null이면 하트비트가 없는 에이전트다. */
    private UUID seedAgent(String hostname, String osPlatform, String status,
                           String agentVersion, Instant lastHeartbeatAt) {
        UUID deviceId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO devices (device_id, hostname, os_platform) VALUES (?, ?, ?)",
                deviceId, hostname, osPlatform);
        jdbcTemplate.update(
                """
                INSERT INTO agents (agent_id, device_id, agent_version, enrollment_token_hash, status, last_heartbeat_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                agentId, deviceId, agentVersion, "test-hash-" + agentId, status,
                lastHeartbeatAt == null ? null : Timestamp.from(lastHeartbeatAt));
        return agentId;
    }

    @Test
    void 기본은_최근_하트비트_순으로_주고_필드와_페이지_정보를_채운다() {
        String prefix = newPrefix();
        UUID older = seedAgent(prefix + "-old", "WINDOWS", "ACTIVE", "0.2.0", Instant.parse("2026-10-20T01:00:00Z"));
        UUID newer = seedAgent(prefix + "-new", "MACOS", "ACTIVE", "0.3.0", Instant.parse("2026-10-20T02:00:00Z"));

        ResponseEntity<Map<String, Object>> response = get("hostname=" + prefix);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> items = content(response);
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("agentId")).isEqualTo(newer.toString());   // 최근 하트비트가 위
        assertThat(items.get(1).get("agentId")).isEqualTo(older.toString());
        Map<String, Object> first = items.get(0);
        assertThat(first.get("hostname")).isEqualTo(prefix + "-new");
        assertThat(first.get("osPlatform")).isEqualTo("MACOS");
        assertThat(first.get("agentVersion")).isEqualTo("0.3.0");
        assertThat(first.get("status")).isEqualTo("ACTIVE");
        assertThat(Instant.parse((String) first.get("lastHeartbeatAt"))).isEqualTo(Instant.parse("2026-10-20T02:00:00Z"));
        // 토큰 해시는 응답에 없다
        assertThat(first).doesNotContainKeys(
                "enrollmentTokenHash", "agentTokenHash", "enrollment_token_hash", "agent_token_hash");
        Map<String, Object> body = response.getBody();
        assertThat(body.get("page")).isEqualTo(0);
        assertThat(body.get("size")).isEqualTo(20);
        assertThat(body.get("totalElements")).isEqualTo(2);
        assertThat(body.get("totalPages")).isEqualTo(1);
        assertThat(body).doesNotContainKeys("items", "nextCursor");
    }

    @Test
    void 하트비트가_없는_에이전트는_맨_뒤이고_lastHeartbeatAt이_null이다() {
        String prefix = newPrefix();
        UUID neverBeat = seedAgent(prefix + "-fresh", "LINUX", "ACTIVE", "0.3.0", null);
        UUID beat = seedAgent(prefix + "-beat", "LINUX", "ACTIVE", "0.3.0", Instant.parse("2026-10-20T01:00:00Z"));

        List<Map<String, Object>> items = content(get("hostname=" + prefix));

        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("agentId")).isEqualTo(beat.toString());
        assertThat(items.get(1).get("agentId")).isEqualTo(neverBeat.toString());   // 하트비트가 없으면 맨 뒤
        assertThat(items.get(1).get("lastHeartbeatAt")).isNull();
    }

    @Test
    void status로_거른다() {
        String prefix = newPrefix();
        seedAgent(prefix + "-active", "WINDOWS", "ACTIVE", "0.3.0", Instant.parse("2026-10-20T01:00:00Z"));
        UUID revoked = seedAgent(prefix + "-revoked", "WINDOWS", "REVOKED", "0.3.0", Instant.parse("2026-10-20T02:00:00Z"));

        List<Map<String, Object>> items = content(get("hostname=" + prefix + "&status=REVOKED"));

        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("agentId")).isEqualTo(revoked.toString());
        assertThat(items.get(0).get("status")).isEqualTo("REVOKED");
    }

    @Test
    void osPlatform으로_거른다() {
        String prefix = newPrefix();
        seedAgent(prefix + "-win", "WINDOWS", "ACTIVE", "0.3.0", Instant.parse("2026-10-20T01:00:00Z"));
        UUID mac = seedAgent(prefix + "-mac", "MACOS", "ACTIVE", "0.3.0", Instant.parse("2026-10-20T02:00:00Z"));

        List<Map<String, Object>> items = content(get("hostname=" + prefix + "&osPlatform=MACOS"));

        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("agentId")).isEqualTo(mac.toString());
    }

    @Test
    void hostname은_부분_일치이고_대소문자를_구분하지_않는다() {
        String prefix = newPrefix();
        UUID target = seedAgent("Hangjung-" + prefix, "MACOS", "ACTIVE", "0.3.0", Instant.parse("2026-10-20T01:00:00Z"));
        seedAgent("Other-" + prefix, "MACOS", "ACTIVE", "0.3.0", Instant.parse("2026-10-20T02:00:00Z"));

        // 호스트 이름의 일부(대문자)로 찾는다. 앞부분이 아니라 중간 일부여도 된다.
        List<Map<String, Object>> items = content(get("hostname=HANGJUNG-" + prefix.toUpperCase()));

        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("agentId")).isEqualTo(target.toString());
    }

    @Test
    void hostname의_밑줄은_와일드카드가_아니라_글자_그대로_찾는다() {
        String prefix = newPrefix();
        seedAgent(prefix + "-pc", "LINUX", "ACTIVE", "0.3.0", Instant.parse("2026-10-20T01:00:00Z"));

        // LIKE였다면 _가 아무 한 글자(-)에 맞아서 찾아졌을 것이다. 글자 그대로 찾으니 0건이다.
        List<Map<String, Object>> items = content(get("hostname=" + prefix + "_"));

        assertThat(items).isEmpty();
    }

    @Test
    void page와_size로_나눠서_준다() {
        String prefix = newPrefix();
        seedAgent(prefix + "-1", "LINUX", "ACTIVE", "0.3.0", Instant.parse("2026-10-20T01:00:00Z"));
        seedAgent(prefix + "-2", "LINUX", "ACTIVE", "0.3.0", Instant.parse("2026-10-20T02:00:00Z"));
        seedAgent(prefix + "-3", "LINUX", "ACTIVE", "0.3.0", Instant.parse("2026-10-20T03:00:00Z"));

        ResponseEntity<Map<String, Object>> firstPage = get("hostname=" + prefix + "&page=0&size=2");
        ResponseEntity<Map<String, Object>> secondPage = get("hostname=" + prefix + "&page=1&size=2");

        assertThat(content(firstPage)).hasSize(2);
        assertThat(firstPage.getBody().get("totalElements")).isEqualTo(3);
        assertThat(firstPage.getBody().get("totalPages")).isEqualTo(2);
        assertThat(content(secondPage)).hasSize(1);
        assertThat(secondPage.getBody().get("page")).isEqualTo(1);
    }

    @Test
    void 잘못된_파라미터는_400_INVALID_INPUT이다() {
        List<String> badQueries = List.of(
                "status=NOPE",
                "status=active",      // 소문자는 허용하지 않는다
                "osPlatform=NOPE",
                "page=-1",
                "size=0",
                "size=201");

        for (String query : badQueries) {
            ResponseEntity<Map<String, Object>> response = get(query);
            assertThat(response.getStatusCode()).as(query).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().get("code")).as(query).isEqualTo("INVALID_INPUT");
        }
    }

    @Test
    void 빈_문자열_필터는_걸지_않은_것과_같다() {
        ResponseEntity<Map<String, Object>> response = get("status=&osPlatform=&hostname=");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void 로그인하지_않으면_401이다() {
        ResponseEntity<Map<String, Object>> response = restTemplate.getForEntity(URL, mapType());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code")).isEqualTo("UNAUTHORIZED");
    }
}