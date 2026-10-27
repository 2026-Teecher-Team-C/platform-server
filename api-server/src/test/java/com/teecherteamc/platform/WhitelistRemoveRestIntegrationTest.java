package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

// 로그인이 필요한 API라 AdminApiClient로 로그인한 access 토큰으로 요청한다. ANALYST 권한 게이트는 범위 밖.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfiguration.class, AdminApiClient.class})
class WhitelistRemoveRestIntegrationTest {

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
    private ResponseEntity<Map<String, Object>> remove(String sha256) {
        return adminClient.exchange(URL + "/" + sha256, HttpMethod.DELETE, null,
                (Class<Map<String, Object>>) (Class<?>) Map.class);
    }

    private UUID seedAdmin() {
        UUID adminId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO admin_users (admin_id, username, password_hash) VALUES (?, ?, 'unused')",
                adminId, "seed-" + adminId);
        return adminId;
    }

    private void seedWhitelist(String sha256, boolean active, Instant expiresAt) {
        jdbcTemplate.update(
                """
                INSERT INTO hash_whitelist (hash_type, hash_value, reason, added_by, is_active, expires_at)
                VALUES ('SHA256', ?, 'seeded', ?, ?, ?)
                """,
                sha256, seedAdmin(), active, expiresAt == null ? null : Timestamp.from(expiresAt));
    }

    private Boolean isActive(String sha256) {
        return jdbcTemplate.queryForObject(
                "SELECT is_active FROM hash_whitelist WHERE hash_value = ?", Boolean.class, sha256);
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
    void 해제하면_204이고_행은_남고_비활성이며_감사_로그가_남는다() {
        String sha256 = randomSha256();
        seedWhitelist(sha256, true, null);

        ResponseEntity<Map<String, Object>> response = remove(sha256);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(isActive(sha256)).isFalse();
        Map<String, Object> audit = lastAudit("REMOVE_WHITELIST", sha256);
        assertThat(audit.get("actor_id")).isEqualTo(adminClient.adminId());
        assertThat(audit.get("target_type")).isEqualTo("WHITELIST");
        assertThat((String) audit.get("detail")).contains("seeded");
    }

    @Test
    void 대문자_해시로_해제해도_적용된다() {
        String sha256 = randomSha256();
        seedWhitelist(sha256, true, null);

        assertThat(remove(sha256.toUpperCase()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(isActive(sha256)).isFalse();
    }

    @Test
    void 만료됐지만_활성인_항목도_해제할_수_있다() {
        String sha256 = randomSha256();
        seedWhitelist(sha256, true, Instant.now().minus(1, ChronoUnit.DAYS));

        assertThat(remove(sha256).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(isActive(sha256)).isFalse();
    }

    @Test
    void 없는_해시를_해제하면_404() {
        ResponseEntity<Map<String, Object>> response = remove(randomSha256());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("NOT_FOUND");
    }

    @Test
    void 이미_해제된_해시를_해제하면_404() {
        String sha256 = randomSha256();
        seedWhitelist(sha256, false, null);

        assertThat(remove(sha256).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 형식이_틀린_해시를_해제하면_400() {
        assertThat(remove("not-a-hash").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 토큰이_없으면_401() {
        ResponseEntity<String> response = restTemplate.exchange(
                URL + "/" + randomSha256(), HttpMethod.DELETE, HttpEntity.EMPTY, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
