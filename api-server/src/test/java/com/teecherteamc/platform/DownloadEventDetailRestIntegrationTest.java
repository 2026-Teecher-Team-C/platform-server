package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
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

// 인증 없음 — 데모 범위로 명시적으로 생략(2026-10-14 요구사항). VIEWER 권한 게이트는 범위 밖.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
class DownloadEventDetailRestIntegrationTest {

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

    @Test
    void 존재하는_이벤트는_상세_필드가_다_나온다() {
        UUID deviceId = seedDevice("DESKTOP-DETAIL");
        UUID agentId = seedAgent(deviceId);
        UUID eventId = UUID.randomUUID();
        Instant heldAt = Instant.parse("2026-10-23T00:00:00Z");
        Instant decidedAt = heldAt.plusMillis(500);
        jdbcTemplate.update(
                """
                INSERT INTO download_events
                    (event_id, agent_id, request_host, url, filename, mime_type, file_size,
                     pipeline_status, decision, decision_source, cache_hit, bytes_uploaded,
                     held_at, decided_at, hold_duration_ms, created_at)
                VALUES (?, ?, 'malicious.example.com', 'https://malicious.example.com/f.exe', 'f.exe',
                        'application/octet-stream', 1024, 'COMPLETED', 'BLOCKED', 'ENGINE', false, 1024,
                        ?, ?, 500, ?)
                """,
                eventId, agentId, Timestamp.from(heldAt), Timestamp.from(decidedAt), Timestamp.from(decidedAt));

        ResponseEntity<Map<String, Object>> response = restTemplate.getForEntity(
                "/api/admin/download-events/" + eventId, (Class<Map<String, Object>>) (Class<?>) Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("eventId")).isEqualTo(eventId.toString());
        assertThat(body.get("hostname")).isEqualTo("DESKTOP-DETAIL");
        assertThat(body.get("requestHost")).isEqualTo("malicious.example.com");
        assertThat(body.get("url")).isEqualTo("https://malicious.example.com/f.exe");
        assertThat(body.get("bytesUploaded")).isEqualTo(1024);
        assertThat(body.get("heldAt")).isNotNull();
        assertThat(body.get("decidedAt")).isNotNull();
        assertThat(body.get("decision")).isEqualTo("BLOCKED");
    }

    @Test
    void 존재하지_않는_eventId는_404_NOT_FOUND다() {
        ResponseEntity<Map<String, Object>> response = restTemplate.getForEntity(
                "/api/admin/download-events/" + UUID.randomUUID(),
                (Class<Map<String, Object>>) (Class<?>) Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("NOT_FOUND");
    }

    @Test
    void eventId가_UUID_형식이_아니면_400_INVALID_INPUT이다() {
        ResponseEntity<Map<String, Object>> response = restTemplate.getForEntity(
                "/api/admin/download-events/not-a-uuid", (Class<Map<String, Object>>) (Class<?>) Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_INPUT");
    }
}
