package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

// 로그인이 필요한 API라 AdminApiClient로 로그인한 access 토큰으로 요청한다. VIEWER 권한 게이트는 범위 밖.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfiguration.class, AdminApiClient.class})
class QuarantineDetailRestIntegrationTest {

    @Autowired
    private AdminApiClient adminClient;

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

    @Test
    void 존재하는_격리파일은_상세_필드가_다_나온다() {
        UUID deviceId = seedDevice("DESKTOP-Q-DETAIL");
        UUID agentId = seedAgent(deviceId);
        String sha256 = seedFileVerdict();
        UUID eventId = seedDownloadEvent(agentId, sha256);
        UUID quarantineId = seedQuarantineFile(eventId, sha256, agentId);

        ResponseEntity<Map<String, Object>> response = adminClient.getForEntity(
                "/api/admin/quarantine/" + quarantineId, (Class<Map<String, Object>>) (Class<?>) Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("quarantineId")).isEqualTo(quarantineId.toString());
        assertThat(body.get("hostname")).isEqualTo("DESKTOP-Q-DETAIL");
        assertThat(body).doesNotContainKeys("s3Bucket", "s3Key", "bucket", "key");

        @SuppressWarnings("unchecked")
        Map<String, Object> downloadEvent = (Map<String, Object>) body.get("downloadEvent");
        assertThat(downloadEvent.get("eventId")).isEqualTo(eventId.toString());
        assertThat(downloadEvent.get("requestHost")).isEqualTo("malicious.example.com");
        assertThat(downloadEvent.get("url")).isEqualTo("https://malicious.example.com/malware.exe");

        @SuppressWarnings("unchecked")
        Map<String, Object> agent = (Map<String, Object>) body.get("agent");
        assertThat(agent.get("agentId")).isEqualTo(agentId.toString());
        assertThat(agent.get("hostname")).isEqualTo("DESKTOP-Q-DETAIL");
    }

    @Test
    void 존재하지_않는_quarantineId는_404_NOT_FOUND다() {
        ResponseEntity<Map<String, Object>> response = adminClient.getForEntity(
                "/api/admin/quarantine/" + UUID.randomUUID(), (Class<Map<String, Object>>) (Class<?>) Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("NOT_FOUND");
    }

    @Test
    void quarantineId가_UUID_형식이_아니면_400_INVALID_INPUT이다() {
        ResponseEntity<Map<String, Object>> response = adminClient.getForEntity(
                "/api/admin/quarantine/not-a-uuid", (Class<Map<String, Object>>) (Class<?>) Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_INPUT");
    }
}
