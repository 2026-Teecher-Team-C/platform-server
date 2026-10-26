package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

// 로그인이 필요한 API라 AdminApiClient로 로그인한 access 토큰으로 요청한다. ANALYST 권한 게이트는 범위 밖.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfiguration.class, AdminApiClient.class})
class WhitelistUpdateRestIntegrationTest {

    private static final String URL = "/api/admin/whitelist";

    @Autowired
    private AdminApiClient adminClient;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static String randomSha256() {
        byte[] bytes = new byte[32];
        ThreadLocalRandom.current().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map<String, Object>> update(String sha256, String reason) {
        return adminClient.exchange(URL + "/" + sha256, HttpMethod.PATCH, Map.of("reason", reason),
                (Class<Map<String, Object>>) (Class<?>) Map.class);
    }

    /** 시드 관리자를 만들어 그 이름으로 등록한 항목을 넣고, 등록자 id를 돌려준다. */
    private UUID seedWhitelist(String sha256, boolean active) {
        UUID adminId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO admin_users (admin_id, username, password_hash) VALUES (?, ?, 'unused')",
                adminId, "seed-" + adminId);
        jdbcTemplate.update(
                """
                INSERT INTO hash_whitelist (hash_type, hash_value, reason, added_by, is_active)
                VALUES ('SHA256', ?, 'seeded', ?, ?)
                """,
                sha256, adminId, active);
        return adminId;
    }

    private Map<String, Object> lastAudit(String action, String sha256) {
        return jdbcTemplate.queryForMap(
                """
                SELECT actor_id, target_type, target_id, detail::text AS detail FROM audit_logs
                WHERE action = ? AND target_id = ? ORDER BY created_at DESC LIMIT 1
                """,
                action, "SHA256:" + sha256);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 사유를_수정하면_200과_바뀐_항목을_돌려준다() {
        String sha256 = randomSha256();
        UUID addedBy = seedWhitelist(sha256, true);

        ResponseEntity<Map<String, Object>> response = update(sha256, "사내 배포 도구 v2");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("hashValue")).isEqualTo(sha256);
        assertThat(body.get("reason")).isEqualTo("사내 배포 도구 v2");
        assertThat(body.get("isActive")).isEqualTo(true);
        assertThat(body.get("origin")).isEqualTo("PREREGISTERED");
        // 등록자는 수정한 관리자가 아니라 처음 등록한 관리자 그대로다.
        Map<String, Object> addedByBody = (Map<String, Object>) body.get("addedBy");
        assertThat(addedByBody.get("adminId")).isEqualTo(addedBy.toString());
        assertThat(addedByBody.get("username")).isEqualTo("seed-" + addedBy);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reason FROM hash_whitelist WHERE hash_value = ?", String.class, sha256))
                .isEqualTo("사내 배포 도구 v2");
    }

    @Test
    void 수정하면_감사_로그에_이전_이후_사유가_남는다() {
        String sha256 = randomSha256();
        seedWhitelist(sha256, true);

        update(sha256, "사내 배포 도구 v2");

        Map<String, Object> audit = lastAudit("UPDATE_WHITELIST", sha256);
        assertThat(audit.get("actor_id")).isEqualTo(adminClient.adminId());
        assertThat(audit.get("target_type")).isEqualTo("WHITELIST");
        assertThat((String) audit.get("detail")).contains("seeded").contains("사내 배포 도구 v2");
    }

    @Test
    void 대문자_해시로_수정해도_적용된다() {
        String sha256 = randomSha256();
        seedWhitelist(sha256, true);

        assertThat(update(sha256.toUpperCase(), "바뀐 사유").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void 빈_사유로_수정하면_400() {
        String sha256 = randomSha256();
        seedWhitelist(sha256, true);

        assertThat(update(sha256, " ").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 형식이_틀린_해시를_수정하면_400() {
        assertThat(update("not-a-hash", "r").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 없는_해시를_수정하면_404() {
        ResponseEntity<Map<String, Object>> response = update(randomSha256(), "r");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("NOT_FOUND");
    }

    @Test
    void 해제된_해시를_수정하면_404() {
        String sha256 = randomSha256();
        seedWhitelist(sha256, false);

        assertThat(update(sha256, "r").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 토큰이_없으면_401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.exchange(URL + "/" + randomSha256(), HttpMethod.PATCH,
                new HttpEntity<>("{\"reason\":\"r\"}", headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
