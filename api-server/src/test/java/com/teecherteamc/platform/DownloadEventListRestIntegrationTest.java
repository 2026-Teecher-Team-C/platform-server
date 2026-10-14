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

// 인증 없음 — 데모 범위로 명시적으로 생략(2026-10-14 요구사항). VIEWER 권한 게이트는 범위 밖.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
class DownloadEventListRestIntegrationTest {

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

    private UUID seedEvent(UUID agentId, String decision, Instant createdAt) {
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO download_events
                    (event_id, agent_id, request_host, url, filename, mime_type, file_size,
                     pipeline_status, decision, decision_source, cache_hit, bytes_uploaded,
                     held_at, decided_at, hold_duration_ms, created_at)
                VALUES (?, ?, 'example.com', 'https://example.com/f', 'f.exe', 'application/octet-stream',
                        1024, 'COMPLETED', ?, 'ENGINE', false, 0, ?, ?, 10, ?)
                """,
                eventId, agentId, decision, Timestamp.from(createdAt), Timestamp.from(createdAt),
                Timestamp.from(createdAt));
        return eventId;
    }

    @Test
    void hostname이_devices_조인으로_채워진다() {
        UUID deviceId = seedDevice("DESKTOP-ABC");
        UUID agentId = seedAgent(deviceId);
        seedEvent(agentId, "BLOCKED", Instant.parse("2026-10-14T00:00:00Z"));

        // 같은 DB를 공유하는 다른 테스트가 만든 행이 섞이지 않도록 agentId로 이 테스트의 행만 본다.
        ResponseEntity<Map<String, Object>> response = restTemplate.getForEntity(
                "/api/admin/download-events?agentId=" + agentId,
                (Class<Map<String, Object>>) (Class<?>) Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> items = (List<Map<String, Object>>) response.getBody().get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("hostname")).isEqualTo("DESKTOP-ABC");
    }

    @Test
    void agentId로_필터링된다() {
        UUID deviceId = seedDevice("host-a");
        UUID agentA = seedAgent(deviceId);
        UUID agentB = seedAgent(deviceId);
        seedEvent(agentA, "BLOCKED", Instant.parse("2026-10-14T01:00:00Z"));
        seedEvent(agentB, "BLOCKED", Instant.parse("2026-10-14T01:00:00Z"));

        ResponseEntity<Map<String, Object>> response = restTemplate.getForEntity(
                "/api/admin/download-events?agentId=" + agentA,
                (Class<Map<String, Object>>) (Class<?>) Map.class);

        List<Map<String, Object>> items = (List<Map<String, Object>>) response.getBody().get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("agentId")).isEqualTo(agentA.toString());
    }

    @Test
    void decision으로_필터링된다() {
        UUID deviceId = seedDevice("host-b");
        UUID agentId = seedAgent(deviceId);
        seedEvent(agentId, "BLOCKED", Instant.parse("2026-10-14T02:00:00Z"));
        seedEvent(agentId, "RELEASED", Instant.parse("2026-10-14T02:00:00Z"));

        // agentId도 같이 걸어서 다른 테스트가 만든 BLOCKED 행과 안 섞이게 한다(같은 DB를 공유함).
        ResponseEntity<Map<String, Object>> response = restTemplate.getForEntity(
                "/api/admin/download-events?decision=BLOCKED&agentId=" + agentId,
                (Class<Map<String, Object>>) (Class<?>) Map.class);

        List<Map<String, Object>> items = (List<Map<String, Object>>) response.getBody().get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("decision")).isEqualTo("BLOCKED");
    }

    @Test
    void created_at_내림차순으로_정렬되고_동일시각이면_event_id_내림차순이다() {
        UUID deviceId = seedDevice("host-c");
        UUID agentId = seedAgent(deviceId);
        Instant sameInstant = Instant.parse("2026-10-14T03:00:00Z");
        UUID older = seedEvent(agentId, "BLOCKED", Instant.parse("2026-10-14T02:59:00Z"));
        UUID tie1 = seedEvent(agentId, "BLOCKED", sameInstant);
        UUID tie2 = seedEvent(agentId, "BLOCKED", sameInstant);

        ResponseEntity<Map<String, Object>> response = restTemplate.getForEntity(
                "/api/admin/download-events?agentId=" + agentId,
                (Class<Map<String, Object>>) (Class<?>) Map.class);

        List<Map<String, Object>> items = (List<Map<String, Object>>) response.getBody().get("items");
        assertThat(items).hasSize(3);
        UUID expectedFirst = tie1.compareTo(tie2) > 0 ? tie1 : tie2;
        UUID expectedSecond = tie1.compareTo(tie2) > 0 ? tie2 : tie1;
        assertThat(items.get(0).get("eventId")).isEqualTo(expectedFirst.toString());
        assertThat(items.get(1).get("eventId")).isEqualTo(expectedSecond.toString());
        assertThat(items.get(2).get("eventId")).isEqualTo(older.toString());
    }

    @Test
    void agentId가_UUID_형식이_아니면_400이다() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/api/admin/download-events?agentId=not-a-uuid", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void decision이_유효하지_않은_값이면_400이다() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/api/admin/download-events?decision=NOPE", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void limit이_0이거나_200초과면_400이다() {
        assertThat(restTemplate.getForEntity("/api/admin/download-events?limit=0", String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(restTemplate.getForEntity("/api/admin/download-events?limit=201", String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void nextCursor는_항상_null이다() {
        UUID deviceId = seedDevice("host-d");
        UUID agentId = seedAgent(deviceId);
        seedEvent(agentId, "BLOCKED", Instant.parse("2026-10-14T04:00:00Z"));

        ResponseEntity<Map<String, Object>> response = restTemplate.getForEntity(
                "/api/admin/download-events?agentId=" + agentId,
                (Class<Map<String, Object>>) (Class<?>) Map.class);

        assertThat(response.getBody()).containsKey("nextCursor");
        assertThat(response.getBody().get("nextCursor")).isNull();
    }
}
