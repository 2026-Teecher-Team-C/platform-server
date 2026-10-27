package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.teecherteamc.platform.hashlist.application.AddWhitelistEntryUseCase;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
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
import org.springframework.transaction.support.TransactionTemplate;

// 로그인이 필요한 API라 AdminApiClient로 로그인한 access 토큰으로 요청한다. ANALYST 권한 게이트는 범위 밖.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfiguration.class, AdminApiClient.class})
class WhitelistRegisterRestIntegrationTest {

    private static final String URL = "/api/admin/whitelist";
    private static final Instant CREATED_AT = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    private AdminApiClient adminClient;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AddWhitelistEntryUseCase addWhitelistEntryUseCase;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private static String randomSha256() {
        byte[] bytes = new byte[32];
        ThreadLocalRandom.current().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map<String, Object>> create(String sha256, String reason, Instant expiresAt) {
        Map<String, Object> body = new HashMap<>();
        body.put("sha256", sha256);
        body.put("reason", reason);
        body.put("expiresAt", expiresAt == null ? null : expiresAt.toString());
        return adminClient.postForEntity(URL, body, (Class<Map<String, Object>>) (Class<?>) Map.class);
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
                INSERT INTO hash_whitelist (hash_type, hash_value, reason, added_by, is_active, expires_at, created_at)
                VALUES ('SHA256', ?, 'seeded', ?, ?, ?, ?)
                """,
                sha256, seedAdmin(), active,
                expiresAt == null ? null : Timestamp.from(expiresAt), Timestamp.from(CREATED_AT));
    }

    // origin_quarantine_id가 quarantine_files FK라 장치 → 에이전트 → 판정 → 이벤트 → 격리 사슬을 만든다.
    private UUID seedQuarantine(String sha256) {
        UUID deviceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO devices (device_id, hostname, os_platform) VALUES (?, 'DESKTOP-WL', 'WINDOWS')",
                deviceId);
        UUID agentId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO agents (agent_id, device_id, agent_version, enrollment_token_hash)
                VALUES (?, ?, '0.0.1', ?)
                """,
                agentId, deviceId, "hash-" + agentId);
        jdbcTemplate.update(
                "INSERT INTO file_verdicts (sha256, verdict, verdict_source) VALUES (?, 'MALICIOUS', 'ENGINE')",
                sha256);
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO download_events
                    (event_id, agent_id, sha256, request_host, url, filename, mime_type, file_size,
                     pipeline_status, decision, decision_source, cache_hit, bytes_uploaded,
                     held_at, decided_at, hold_duration_ms, created_at)
                VALUES (?, ?, ?, 'example.com', 'https://example.com/f', 'f.exe',
                        'application/octet-stream', 1024, 'COMPLETED', 'BLOCKED', 'ENGINE', false, 1024,
                        now(), now(), 500, now())
                """,
                eventId, agentId, sha256);
        UUID quarantineId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO quarantine_files
                    (quarantine_id, event_id, sha256, agent_id, original_filename, file_size, s3_bucket, s3_key, status)
                VALUES (?, ?, ?, ?, 'f.exe', 1024, 'test-bucket', ?, 'QUARANTINED')
                """,
                quarantineId, eventId, sha256, agentId, "key-" + quarantineId);
        return quarantineId;
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

    // stale 처리는 verdict 컨텍스트가 이벤트로 받아서 한다. 등록과 같은 트랜잭션이라 등록이 취소되면 함께 취소된다.
    @Test
    void 등록이_롤백되면_stale_처리도_롤백된다() {
        String sha256 = randomSha256();
        jdbcTemplate.update(
                "INSERT INTO file_verdicts (sha256, verdict, verdict_source) VALUES (?, 'MALICIOUS', 'ENGINE')",
                sha256);
        UUID adminId = seedAdmin();

        transactionTemplate.executeWithoutResult(status -> {
            addWhitelistEntryUseCase.add(
                    new AddWhitelistEntryUseCase.Command(sha256, "오탐", null, adminId, "127.0.0.1"));
            status.setRollbackOnly();
        });

        assertThat(jdbcTemplate.queryForObject(
                "SELECT is_stale FROM file_verdicts WHERE sha256 = ?", Boolean.class, sha256)).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hash_whitelist WHERE hash_value = ?", Integer.class, sha256)).isZero();
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
    void 해제된_블랙리스트_항목은_등록을_막지_않는다() {
        String sha256 = randomSha256();
        jdbcTemplate.update(
                """
                INSERT INTO hash_blacklist (hash_type, hash_value, reason, is_active, deactivated_at)
                VALUES ('SHA256', ?, 'revoked', false, now())
                """,
                sha256);

        assertThat(create(sha256, "오탐", null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void 이미_활성이면_409() {
        String sha256 = randomSha256();
        seedWhitelist(sha256, true, null);

        ResponseEntity<Map<String, Object>> response = create(sha256, "중복", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("message")).isEqualTo("이미 화이트리스트에 등록된 해시입니다.");
    }

    @Test
    void 해제했던_해시를_다시_등록하면_재활성화된다() {
        String sha256 = randomSha256();
        seedWhitelist(sha256, false, null);

        ResponseEntity<Map<String, Object>> response = create(sha256, "다시 허용", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("reason")).isEqualTo("다시 허용");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT is_active, added_by, created_at FROM hash_whitelist WHERE hash_value = ?", sha256);
        assertThat(row.get("is_active")).isEqualTo(true);
        assertThat(row.get("added_by")).isEqualTo(adminClient.adminId());
        assertThat(((Timestamp) row.get("created_at")).toInstant()).isEqualTo(CREATED_AT);
    }

    @Test
    void 만료됐지만_활성으로_남은_항목은_다시_등록하면_새로_갱신된다() {
        String sha256 = randomSha256();
        seedWhitelist(sha256, true, Instant.now().minus(1, ChronoUnit.DAYS));

        ResponseEntity<Map<String, Object>> response = create(sha256, "다시 허용", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("expiresAt")).isNull();
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT is_active, expires_at FROM hash_whitelist WHERE hash_value = ?", sha256);
        assertThat(row.get("is_active")).isEqualTo(true);
        assertThat(row.get("expires_at")).isNull();
    }

    @Test
    void 복원으로_들어왔던_항목을_직접_재등록하면_복원_출처가_비워진다() {
        String sha256 = randomSha256();
        jdbcTemplate.update(
                """
                INSERT INTO hash_whitelist (hash_type, hash_value, reason, added_by, origin_quarantine_id, is_active)
                VALUES ('SHA256', ?, 'restored', ?, ?, false)
                """,
                sha256, seedAdmin(), seedQuarantine(sha256));

        ResponseEntity<Map<String, Object>> response = create(sha256, "직접 등록", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("origin")).isEqualTo("PREREGISTERED");
        assertThat(response.getBody().get("originQuarantineId")).isNull();
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
}
