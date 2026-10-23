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

// 인증 없음 — 데모 범위로 명시적으로 생략(다운로드 이벤트 목록과 같은 이유). VIEWER 권한 게이트는 범위 밖.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
class QuarantineListRestIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    private String seedFileVerdict() {
        String sha256 = UUID.randomUUID().toString().replace("-", "").repeat(2).substring(0, 64);
        jdbcTemplate.update(
                "INSERT INTO file_verdicts (sha256, verdict, verdict_source) VALUES (?, 'MALICIOUS', 'MANUAL')",
                sha256);
        return sha256;
    }

    private UUID seedDownloadEvent(UUID agentId, String sha256) {
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
        return eventId;
    }

    private UUID seedQuarantineFile(UUID eventId, String sha256, UUID agentId, String status, Instant quarantinedAt) {
        UUID quarantineId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO quarantine_files
                    (quarantine_id, event_id, sha256, agent_id, original_filename, file_size,
                     s3_bucket, s3_key, status, quarantined_at)
                VALUES (?, ?, ?, ?, 'malware.exe', 1024, 'test-bucket', ?, ?, ?)
                """,
                quarantineId, eventId, sha256, agentId, "key-" + quarantineId, status, Timestamp.from(quarantinedAt));
        return quarantineId;
    }

    private UUID seedFullChain(String hostname, String status, Instant quarantinedAt) {
        UUID deviceId = seedDevice(hostname);
        UUID agentId = seedAgent(deviceId);
        String sha256 = seedFileVerdict();
        UUID eventId = seedDownloadEvent(agentId, sha256);
        return seedQuarantineFile(eventId, sha256, agentId, status, quarantinedAt);
    }

    @Test
    void hostname이_devices_조인으로_채워지고_S3_위치는_노출되지_않는다() {
        seedFullChain("DESKTOP-QUARANTINE", "QUARANTINED", Instant.parse("2026-10-23T00:00:00Z"));

        ResponseEntity<Map<String, Object>> response = restTemplate.getForEntity(
                "/api/admin/quarantine", (Class<Map<String, Object>>) (Class<?>) Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> content = (List<Map<String, Object>>) response.getBody().get("content");
        assertThat(content).isNotEmpty();
        Map<String, Object> item = content.stream()
                .filter(row -> "DESKTOP-QUARANTINE".equals(row.get("hostname")))
                .findFirst()
                .orElseThrow();
        assertThat(item).doesNotContainKeys("s3Bucket", "s3Key", "bucket", "key");
    }

    @Test
    void agentId로_필터링된다() {
        UUID deviceId = seedDevice("host-filter");
        UUID agentA = seedAgent(deviceId);
        UUID agentB = seedAgent(deviceId);
        String shaA = seedFileVerdict();
        String shaB = seedFileVerdict();
        seedQuarantineFile(seedDownloadEvent(agentA, shaA), shaA, agentA, "QUARANTINED", Instant.now());
        seedQuarantineFile(seedDownloadEvent(agentB, shaB), shaB, agentB, "QUARANTINED", Instant.now());

        ResponseEntity<Map<String, Object>> response = restTemplate.getForEntity(
                "/api/admin/quarantine?agentId=" + agentA, (Class<Map<String, Object>>) (Class<?>) Map.class);

        List<Map<String, Object>> content = (List<Map<String, Object>>) response.getBody().get("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("agentId")).isEqualTo(agentA.toString());
    }

    @Test
    void status로_필터링된다() {
        UUID deviceId = seedDevice("host-status");
        UUID agentId = seedAgent(deviceId);
        String sha1 = seedFileVerdict();
        String sha2 = seedFileVerdict();
        seedQuarantineFile(seedDownloadEvent(agentId, sha1), sha1, agentId, "QUARANTINED", Instant.now());

        // status='RESTORED'는 ck_quarantine_files_restored 때문에 restored_at/restored_by가
        // 같은 INSERT에 처음부터 다 있어야 한다(중간에 QUARANTINED로 넣고 나중에 UPDATE하면
        // 그 중간 상태 자체가 제약 위반). restored_by는 admin_users FK라 먼저 행을 만들어 둔다.
        UUID adminId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO admin_users (admin_id, username, password_hash) VALUES (?, ?, 'hash')",
                adminId, "tester-" + adminId);
        UUID eventId2 = seedDownloadEvent(agentId, sha2);
        UUID restoredId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO quarantine_files
                    (quarantine_id, event_id, sha256, agent_id, original_filename, file_size,
                     s3_bucket, s3_key, status, quarantined_at, restored_at, restored_by)
                VALUES (?, ?, ?, ?, 'malware.exe', 1024, 'test-bucket', ?, 'RESTORED', now(), now(), ?)
                """,
                restoredId, eventId2, sha2, agentId, "key-" + restoredId, adminId);

        ResponseEntity<Map<String, Object>> response = restTemplate.getForEntity(
                "/api/admin/quarantine?status=RESTORED&agentId=" + agentId,
                (Class<Map<String, Object>>) (Class<?>) Map.class);

        List<Map<String, Object>> content = (List<Map<String, Object>>) response.getBody().get("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("status")).isEqualTo("RESTORED");
    }

    @Test
    void quarantined_at_내림차순으로_정렬된다() {
        UUID deviceId = seedDevice("host-order");
        UUID agentId = seedAgent(deviceId);
        String shaOld = seedFileVerdict();
        String shaNew = seedFileVerdict();
        UUID older = seedQuarantineFile(
                seedDownloadEvent(agentId, shaOld), shaOld, agentId, "QUARANTINED",
                Instant.parse("2026-10-20T00:00:00Z"));
        UUID newer = seedQuarantineFile(
                seedDownloadEvent(agentId, shaNew), shaNew, agentId, "QUARANTINED",
                Instant.parse("2026-10-23T00:00:00Z"));

        ResponseEntity<Map<String, Object>> response = restTemplate.getForEntity(
                "/api/admin/quarantine?agentId=" + agentId, (Class<Map<String, Object>>) (Class<?>) Map.class);

        List<Map<String, Object>> content = (List<Map<String, Object>>) response.getBody().get("content");
        assertThat(content).hasSize(2);
        assertThat(content.get(0).get("quarantineId")).isEqualTo(newer.toString());
        assertThat(content.get(1).get("quarantineId")).isEqualTo(older.toString());
    }

    @Test
    void status가_유효하지_않은_값이면_400이다() {
        ResponseEntity<Map<String, Object>> response = restTemplate.getForEntity(
                "/api/admin/quarantine?status=NOPE", (Class<Map<String, Object>>) (Class<?>) Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_INPUT");
    }
}
