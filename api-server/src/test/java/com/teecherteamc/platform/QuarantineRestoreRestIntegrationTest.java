package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.teecherteamc.platform.proto.verdict.v1.CheckHashRequest;
import com.teecherteamc.platform.proto.verdict.v1.CheckHashResponse;
import com.teecherteamc.platform.proto.verdict.v1.Decision;
import com.teecherteamc.platform.proto.verdict.v1.DecisionSource;
import com.teecherteamc.platform.proto.verdict.v1.VerdictServiceGrpc;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.grpc.client.ImportGrpcClients;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * POST /api/admin/quarantine/{quarantineId}/restore. 로그인이 필요한 경로라서 AdminApiClient로
 * 로그인한 관리자로 요청한다. 7번 케이스는 복원이 실제로 CheckHash 조회 사슬을 통과시키는지까지
 * 검증한다(PR #46으로 화이트리스트가 조회 사슬에 연결된 것을 전제한다).
 */
@SuppressWarnings("unchecked")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@AutoConfigureTestGrpcTransport
@ImportGrpcClients(types = VerdictServiceGrpc.VerdictServiceBlockingStub.class)
@Import({TestcontainersConfiguration.class, AdminApiClient.class})
class QuarantineRestoreRestIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AdminApiClient adminApi;

    @Autowired
    private VerdictServiceGrpc.VerdictServiceBlockingStub verdictStub;

    private static Class<Map<String, Object>> mapType() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }

    private String restoreUrl(UUID quarantineId) {
        return "/api/admin/quarantine/" + quarantineId + "/restore";
    }

    private UUID seedDevice(String hostname) {
        UUID deviceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO devices (device_id, hostname, os_platform) VALUES (?, ?, 'WINDOWS')",
                deviceId, hostname);
        return deviceId;
    }

    private UUID seedAgent(UUID deviceId) {
        UUID agentId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO agents (agent_id, device_id, agent_version, enrollment_token_hash)
                VALUES (?, ?, '0.0.1', ?)
                """,
                agentId, deviceId, "hash-" + agentId);
        return agentId;
    }

    // UUID 둘을 이으면 소문자 hex 64자다.
    private static String randomSha256() {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
    }

    private void seedFileVerdict(String sha256) {
        jdbcTemplate.update(
                "INSERT INTO file_verdicts (sha256, verdict, verdict_source) VALUES (?, 'MALICIOUS', 'ENGINE')",
                sha256);
    }

    private UUID seedDownloadEvent(UUID agentId, String sha256) {
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO download_events
                    (event_id, agent_id, sha256, request_host, url, filename, mime_type, file_size,
                     pipeline_status, decision, decision_source, cache_hit, bytes_uploaded,
                     held_at, decided_at, hold_duration_ms, created_at)
                VALUES (?, ?, ?, 'malicious.example.com', 'https://malicious.example.com/malware.exe', 'f.exe',
                        'application/octet-stream', 1024, 'COMPLETED', 'BLOCKED', 'ENGINE', false, 1024,
                        now(), now(), 500, now())
                """,
                eventId, agentId, sha256);
        return eventId;
    }

    private UUID seedQuarantineFile(UUID eventId, String sha256, UUID agentId) {
        UUID quarantineId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO quarantine_files
                    (quarantine_id, event_id, sha256, agent_id, original_filename, file_size,
                     s3_bucket, s3_key, status, quarantined_at)
                VALUES (?, ?, ?, ?, 'malware.exe', 1024, 'test-bucket', ?, 'QUARANTINED', ?)
                """,
                quarantineId, eventId, sha256, agentId, "key-" + quarantineId, Timestamp.from(Instant.now()));
        return quarantineId;
    }

    private UUID seedQuarantinedHash(String hostname) {
        UUID deviceId = seedDevice(hostname);
        UUID agentId = seedAgent(deviceId);
        String sha256 = randomSha256();
        seedFileVerdict(sha256);
        UUID eventId = seedDownloadEvent(agentId, sha256);
        return seedQuarantineFile(eventId, sha256, agentId);
    }

    private String sha256Of(UUID quarantineId) {
        return jdbcTemplate.queryForObject(
                "SELECT sha256 FROM quarantine_files WHERE quarantine_id = ?", String.class, quarantineId);
    }

    @Test
    void 정상_복원하면_200과_함께_격리_상태와_화이트리스트_감사_로그가_전부_갱신된다() {
        UUID quarantineId = seedQuarantinedHash("DESKTOP-RESTORE-1");
        String sha256 = sha256Of(quarantineId);

        ResponseEntity<Map<String, Object>> response = adminApi.postForEntity(
                restoreUrl(quarantineId), Map.of("reason", "오탐 확인됨"), mapType());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("quarantineId")).isEqualTo(quarantineId.toString());
        assertThat(body.get("sha256")).isEqualTo(sha256);
        assertThat(body.get("status")).isEqualTo("RESTORED");
        assertThat(body.get("whitelisted")).isEqualTo(true);
        assertThat(body.get("restoredAt")).isNotNull();

        Map<String, Object> quarantineRow = jdbcTemplate.queryForMap(
                "SELECT status, restored_at, restored_by FROM quarantine_files WHERE quarantine_id = ?",
                quarantineId);
        assertThat(quarantineRow.get("status")).isEqualTo("RESTORED");
        assertThat(quarantineRow.get("restored_at")).isNotNull();
        assertThat(quarantineRow.get("restored_by")).isEqualTo(adminApi.adminId());

        Map<String, Object> whitelistRow = jdbcTemplate.queryForMap(
                "SELECT is_active, origin_quarantine_id FROM hash_whitelist WHERE hash_value = ?", sha256);
        assertThat(whitelistRow.get("is_active")).isEqualTo(true);
        assertThat(whitelistRow.get("origin_quarantine_id")).isEqualTo(quarantineId);

        Boolean isStale = jdbcTemplate.queryForObject(
                "SELECT is_stale FROM file_verdicts WHERE sha256 = ?", Boolean.class, sha256);
        assertThat(isStale).isTrue();

        Map<String, Object> auditRow = jdbcTemplate.queryForMap(
                "SELECT actor_id FROM audit_logs WHERE action = 'RESTORE_QUARANTINE' AND target_id = ?",
                quarantineId.toString());
        assertThat(auditRow.get("actor_id")).isEqualTo(adminApi.adminId());
    }

    @Test
    void 해제된_화이트리스트_행이_있으면_재활성화되고_복원출처가_이번_격리로_바뀐다() {
        UUID quarantineId = seedQuarantinedHash("DESKTOP-RESTORE-2");
        String sha256 = sha256Of(quarantineId);
        // origin_quarantine_id가 quarantine_files를 FK로 참조하므로, 다른 해시의 실제 격리 행을 써야 한다.
        UUID otherQuarantineId = seedQuarantinedHash("DESKTOP-RESTORE-2-OTHER");
        jdbcTemplate.update(
                """
                INSERT INTO hash_whitelist (hash_value, reason, added_by, origin_quarantine_id, is_active)
                VALUES (?, '예전 복원', ?, ?, false)
                """,
                sha256, adminApi.adminId(), otherQuarantineId);

        ResponseEntity<Map<String, Object>> response = adminApi.postForEntity(
                restoreUrl(quarantineId), Map.of("reason", "다시 복원"), mapType());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Integer) jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hash_whitelist WHERE hash_value = ?", Integer.class, sha256))
                .isEqualTo(1);   // 새 INSERT가 아니라 기존 행을 되살렸다
        Map<String, Object> whitelistRow = jdbcTemplate.queryForMap(
                "SELECT is_active, origin_quarantine_id, reason FROM hash_whitelist WHERE hash_value = ?", sha256);
        assertThat(whitelistRow.get("is_active")).isEqualTo(true);
        assertThat(whitelistRow.get("origin_quarantine_id")).isEqualTo(quarantineId);
        assertThat(whitelistRow.get("reason")).isEqualTo("다시 복원");
    }

    @Test
    void 이미_활성_화이트리스트면_건드리지_않고도_200을_반환한다() {
        UUID quarantineId = seedQuarantinedHash("DESKTOP-RESTORE-3");
        String sha256 = sha256Of(quarantineId);
        jdbcTemplate.update(
                "INSERT INTO hash_whitelist (hash_value, reason, added_by, is_active) VALUES (?, '기존 등록', ?, true)",
                sha256, adminApi.adminId());

        ResponseEntity<Map<String, Object>> response = adminApi.postForEntity(
                restoreUrl(quarantineId), Map.of("reason", "복원"), mapType());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> whitelistRow = jdbcTemplate.queryForMap(
                "SELECT reason, origin_quarantine_id FROM hash_whitelist WHERE hash_value = ?", sha256);
        assertThat(whitelistRow.get("reason")).isEqualTo("기존 등록");   // 안 건드렸다
        assertThat(whitelistRow.get("origin_quarantine_id")).isNull();
    }

    @Test
    void 존재하지_않는_quarantineId는_404다() {
        ResponseEntity<Map<String, Object>> response = adminApi.postForEntity(
                restoreUrl(UUID.randomUUID()), Map.of(), mapType());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("NOT_FOUND");
    }

    @Test
    void 이미_복원된_격리파일을_또_복원하면_409다() {
        UUID quarantineId = seedQuarantinedHash("DESKTOP-RESTORE-4");
        adminApi.postForEntity(restoreUrl(quarantineId), Map.of(), mapType());

        ResponseEntity<Map<String, Object>> response = adminApi.postForEntity(
                restoreUrl(quarantineId), Map.of(), mapType());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("CONFLICT");
    }

    @Test
    void 로그인하지_않으면_401이다() {
        UUID quarantineId = seedQuarantinedHash("DESKTOP-RESTORE-5");

        ResponseEntity<Map<String, Object>> response = restTemplate.postForEntity(
                restoreUrl(quarantineId), Map.of(), mapType());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code")).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void 복원_직후_같은_해시로_CheckHash를_호출하면_ALLOW_WHITELIST를_반환한다() {
        UUID quarantineId = seedQuarantinedHash("DESKTOP-RESTORE-6");
        String sha256 = sha256Of(quarantineId);

        ResponseEntity<Map<String, Object>> response = adminApi.postForEntity(
                restoreUrl(quarantineId), Map.of("reason", "오탐"), mapType());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        CheckHashResponse checkHashResponse = verdictStub.checkHash(
                CheckHashRequest.newBuilder().setSha256(sha256).build());

        assertThat(checkHashResponse.getDecision()).isEqualTo(Decision.DECISION_ALLOW);
        assertThat(checkHashResponse.getSource()).isEqualTo(DecisionSource.DECISION_SOURCE_WHITELIST);
    }
}
