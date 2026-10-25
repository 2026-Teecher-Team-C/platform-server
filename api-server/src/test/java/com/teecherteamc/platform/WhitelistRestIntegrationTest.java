package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
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
class WhitelistRestIntegrationTest {

    private static final String URL = "/api/admin/whitelist";

    @Autowired
    private AdminApiClient adminClient;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 목록 테스트에서 totalElements를 정확히 보려고 매번 비운다. hash_whitelist를 가리키는 FK는 없다.
    @BeforeEach
    void 테이블을_비운다() {
        jdbcTemplate.update("DELETE FROM hash_whitelist");
    }

    private static String randomSha256() {
        byte[] bytes = new byte[32];
        ThreadLocalRandom.current().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map<String, Object>> call(HttpMethod method, String url, Object body) {
        return adminClient.exchange(url, method, body, (Class<Map<String, Object>>) (Class<?>) Map.class);
    }

    private ResponseEntity<Map<String, Object>> create(String sha256, String reason, Instant expiresAt) {
        Map<String, Object> body = new HashMap<>();
        body.put("sha256", sha256);
        body.put("reason", reason);
        body.put("expiresAt", expiresAt == null ? null : expiresAt.toString());
        return call(HttpMethod.POST, URL, body);
    }

    private UUID seedAdmin() {
        UUID adminId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO admin_users (admin_id, username, password_hash) VALUES (?, ?, 'unused')",
                adminId, "seed-" + adminId);
        return adminId;
    }

    private void seedWhitelist(String sha256, boolean active, Instant expiresAt, Instant createdAt) {
        jdbcTemplate.update(
                """
                INSERT INTO hash_whitelist (hash_type, hash_value, reason, added_by, is_active, expires_at, created_at)
                VALUES ('SHA256', ?, 'seeded', ?, ?, ?, ?)
                """,
                sha256, seedAdmin(), active,
                expiresAt == null ? null : Timestamp.from(expiresAt), Timestamp.from(createdAt));
    }

    private Map<String, Object> lastAudit(String action, String sha256) {
        return jdbcTemplate.queryForMap(
                """
                SELECT actor_id, target_type, target_id, detail::text AS detail FROM audit_logs
                WHERE action = ? AND target_id = ? ORDER BY created_at DESC LIMIT 1
                """,
                action, "SHA256:" + sha256);
    }

    private Boolean isActive(String sha256) {
        return jdbcTemplate.queryForObject(
                "SELECT is_active FROM hash_whitelist WHERE hash_value = ?", Boolean.class, sha256);
    }

    // ---- 등록 POST ----

    @Test
    @SuppressWarnings("unchecked")
    void 등록하면_201과_WhitelistItem을_돌려준다() {
        String sha256 = randomSha256();

        ResponseEntity<Map<String, Object>> response = create(sha256.toUpperCase(), "사내 배포 도구", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("hashType")).isEqualTo("SHA256");
        assertThat(body.get("hashValue")).isEqualTo(sha256);
        assertThat(body.get("reason")).isEqualTo("사내 배포 도구");
        assertThat(body.get("origin")).isEqualTo("PREREGISTERED");
        assertThat(body.get("originQuarantineId")).isNull();
        assertThat(body.get("isActive")).isEqualTo(true);
        assertThat(body.get("expiresAt")).isNull();
        assertThat(body.get("createdAt")).isNotNull();
        Map<String, Object> addedBy = (Map<String, Object>) body.get("addedBy");
        assertThat(addedBy.get("adminId")).isEqualTo(adminClient.adminId().toString());
        assertThat(addedBy.get("username")).isEqualTo(adminClient.username());
    }

    @Test
    void 등록하면_감사_로그가_남는다() {
        String sha256 = randomSha256();

        create(sha256, "사내 배포 도구", null);

        Map<String, Object> audit = lastAudit("ADD_WHITELIST", sha256);
        assertThat(audit.get("actor_id")).isEqualTo(adminClient.adminId());
        assertThat(audit.get("target_type")).isEqualTo("WHITELIST");
        assertThat((String) audit.get("detail")).contains("사내 배포 도구");
    }

    @Test
    void 등록하면_기존_판정이_stale이_된다() {
        String sha256 = randomSha256();
        jdbcTemplate.update(
                "INSERT INTO file_verdicts (sha256, verdict, verdict_source) VALUES (?, 'MALICIOUS', 'ENGINE')",
                sha256);

        create(sha256, "오탐", null);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT is_stale FROM file_verdicts WHERE sha256 = ?", Boolean.class, sha256)).isTrue();
    }

    @Test
    void 만료_시각을_주면_그대로_저장된다() {
        String sha256 = randomSha256();
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

        ResponseEntity<Map<String, Object>> response = create(sha256, "임시 예외", expiresAt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(Instant.parse((String) response.getBody().get("expiresAt"))).isEqualTo(expiresAt);
    }

    @Test
    void 블랙리스트에_있으면_409() {
        String sha256 = randomSha256();
        jdbcTemplate.update(
                "INSERT INTO hash_blacklist (hash_type, hash_value, reason) VALUES ('SHA256', ?, 'known bad')",
                sha256);

        ResponseEntity<Map<String, Object>> response = create(sha256, "오탐", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("CONFLICT");
        assertThat(response.getBody().get("message")).isEqualTo("블랙리스트에 등록된 해시입니다.");
    }

    @Test
    void 이미_활성이면_409() {
        String sha256 = randomSha256();
        seedWhitelist(sha256, true, null, Instant.now());

        ResponseEntity<Map<String, Object>> response = create(sha256, "중복", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("message")).isEqualTo("이미 화이트리스트에 등록된 해시입니다.");
    }

    @Test
    void 해제했던_해시를_다시_등록하면_재활성화된다() {
        String sha256 = randomSha256();
        seedWhitelist(sha256, false, null, Instant.parse("2026-10-01T00:00:00Z"));

        ResponseEntity<Map<String, Object>> response = create(sha256, "다시 허용", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("reason")).isEqualTo("다시 허용");
        assertThat(isActive(sha256)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT added_by FROM hash_whitelist WHERE hash_value = ?", UUID.class, sha256))
                .isEqualTo(adminClient.adminId());
    }

    @Test
    void 형식이_틀리면_400() {
        ResponseEntity<Map<String, Object>> response = create("not-a-hash", "사유", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_INPUT");
    }

    @Test
    void 사유가_비면_400() {
        ResponseEntity<Map<String, Object>> response = create(randomSha256(), " ", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 만료_시각이_과거면_400() {
        String sha256 = randomSha256();

        ResponseEntity<Map<String, Object>> response =
                create(sha256, "사유", Instant.now().minus(1, ChronoUnit.MINUTES));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM hash_whitelist WHERE hash_value = ?", Long.class, sha256)).isZero();
    }

    @Test
    void 토큰이_없으면_401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"sha256\":\"" + randomSha256() + "\",\"reason\":\"r\"}";

        ResponseEntity<String> response = restTemplate.exchange(
                URL, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> content(ResponseEntity<Map<String, Object>> response) {
        return (List<Map<String, Object>>) response.getBody().get("content");
    }
}
