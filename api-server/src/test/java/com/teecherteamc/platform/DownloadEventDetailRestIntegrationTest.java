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

// 로그인이 필요한 API라 AdminApiClient로 로그인한 access 토큰으로 요청한다. 역할 구분은 아직 하지 않는다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfiguration.class, AdminApiClient.class})
class DownloadEventDetailRestIntegrationTest {

    @Autowired
    private AdminApiClient adminClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private org.springframework.boot.resttestclient.TestRestTemplate anonymousClient;

    @Test
    void 목록의_시연용_공개와_상세의_인증_정책을_유지한다() {
        // 기존 SecurityConfig의 시연용 목록 공개는 이번 읽기 DTO 카드에서 바꾸지 않는다.
        assertThat(anonymousClient.getForEntity("/api/admin/download-events", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(anonymousClient.getForEntity("/api/admin/download-events/" + UUID.randomUUID(), String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
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

        ResponseEntity<Map<String, Object>> response = adminClient.getForEntity(
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
        // CheckHash 전용 등 연결 없는 이벤트에 최신 SHA 평가를 추측해 붙이지 않는다.
        assertThat(body).containsKey("similarity");
        assertThat(body.get("similarity")).isNull();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"BLOCK,17", "WARN,45", "NONE,120", "NONE,", "UNAVAILABLE,"})
    void 이벤트_FK의_평가_스냅샷만_공개한다(String classification, Integer distance) {
        UUID agent = seedAgent(seedDevice("TLSH-HISTORY"));
        String sha = UUID.randomUUID().toString().replace("-", "").repeat(2);
        String sample = UUID.randomUUID().toString().replace("-", "").repeat(2);
        UUID assessment = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        Instant assessedAt = Instant.parse("2026-10-23T00:00:00Z");
        jdbcTemplate.update("INSERT INTO file_verdicts(sha256,verdict,verdict_source) VALUES (?,'CLEAN','ENGINE')", sha);
        jdbcTemplate.update("""
                INSERT INTO similarity_assessments(assessment_id,sha256,corpus_token,policy_version,
                    matched_sample_sha256,matched_sample_tlsh,distance,classification,assessed_at)
                VALUES (?,?,'private-token','v1',?,?,?,?,?)
                """, assessment, sha, distance == null ? null : sample,
                distance == null ? null : "T1" + "A".repeat(70), distance, classification, Timestamp.from(assessedAt));
        jdbcTemplate.update("INSERT INTO event_decision_evidence(event_id,agent_id,sha256,assessment_id) VALUES (?,?,?,?)",
                event, agent, sha, assessment);
        jdbcTemplate.update("""
                INSERT INTO download_events(event_id,agent_id,sha256,request_host,url,pipeline_status,decision,decision_source,held_at)
                VALUES (?,?,?,'history.example','https://history.example/test','COMPLETED',?,?,now())
                """, event, agent, sha, classification.equals("BLOCK") ? "BLOCKED" : "RELEASED",
                classification.equals("BLOCK") ? "SIMILARITY" : "ENGINE");
        Map before = adminClient.getForEntity("/api/admin/download-events/" + event, Map.class).getBody();
        Map<String, Object> expected = new java.util.HashMap<>();
        expected.put("classification", classification);
        expected.put("matchedSampleSha256", distance == null ? null : sample);
        expected.put("distance", distance);
        expected.put("assessedAt", assessedAt.toString());
        assertThat(before.get("similarity")).isEqualTo(expected);
        // 최신 SHA 평가와 기준 지문을 바꿔도 과거 이벤트는 정확히 자기 FK를 읽어야 한다.
        jdbcTemplate.update("""
                INSERT INTO similarity_assessments(assessment_id,sha256,corpus_token,policy_version,classification)
                VALUES (?,?,'new-token','v2','UNAVAILABLE')
                """, UUID.randomUUID(), sha);
        jdbcTemplate.update("""
                INSERT INTO file_fingerprints(sha256,tlsh,calculation_status,algorithm_version,provenance)
                VALUES (?,?,'CALCULATED','test','VERIFIED_UPLOAD')
                """, sample, "T1" + "B".repeat(70));
        jdbcTemplate.update("UPDATE file_fingerprints SET tlsh=? WHERE sha256=?", "T1" + "C".repeat(70), sample);
        if (distance != null) {
            Map current = adminClient.getForEntity("/api/admin/samples/" + sample, Map.class).getBody();
            assertThat((Map) current.get("fingerprint")).containsEntry("tlsh", "T1" + "C".repeat(70));
            assertThat(current).containsEntry("blacklist", null);
        }
        Map after = adminClient.getForEntity("/api/admin/download-events/" + event, Map.class).getBody();
        assertThat(after.get("similarity")).isEqualTo(before.get("similarity"));
        Map page = adminClient.getForEntity("/api/admin/download-events?agentId=" + agent, Map.class).getBody();
        Map item = (Map) ((java.util.List<?>) page.get("content")).getFirst();
        assertThat(item).containsEntry("similarityClassification", classification);
        UUID absent = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO download_events(event_id,agent_id,sha256,request_host,url,pipeline_status,decision,decision_source,held_at)
                VALUES (?,?,?,'check.example','https://check.example/test','COMPLETED','RELEASED','CACHE',now())
                """, absent, agent, sha);
        assertThat(adminClient.getForEntity("/api/admin/download-events/" + absent, Map.class).getBody().get("similarity")).isNull();
    }

    @Test
    void 존재하지_않는_eventId는_404_NOT_FOUND다() {
        ResponseEntity<Map<String, Object>> response = adminClient.getForEntity(
                "/api/admin/download-events/" + UUID.randomUUID(),
                (Class<Map<String, Object>>) (Class<?>) Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("NOT_FOUND");
    }

    @Test
    void eventId가_UUID_형식이_아니면_400_INVALID_INPUT이다() {
        ResponseEntity<Map<String, Object>> response = adminClient.getForEntity(
                "/api/admin/download-events/not-a-uuid", (Class<Map<String, Object>>) (Class<?>) Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_INPUT");
    }
}
