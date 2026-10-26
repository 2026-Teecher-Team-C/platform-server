package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

// 로그인이 필요한 API라 AdminApiClient로 로그인한 access 토큰으로 요청한다. ANALYST 권한 게이트는 범위 밖.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfiguration.class, AdminApiClient.class})
class WhitelistListRestIntegrationTest {

    private static final String URL = "/api/admin/whitelist";
    private static final Instant T1 = Instant.parse("2026-10-20T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-21T00:00:00Z");
    private static final Instant T3 = Instant.parse("2026-10-22T00:00:00Z");

    @Autowired
    private AdminApiClient adminClient;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // totalElements를 정확히 보려고 매번 비운다. hash_whitelist를 가리키는 FK는 없다.
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
    private ResponseEntity<Map<String, Object>> list(String query) {
        return adminClient.getForEntity(URL + query, (Class<Map<String, Object>>) (Class<?>) Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> content(ResponseEntity<Map<String, Object>> response) {
        return (List<Map<String, Object>>) response.getBody().get("content");
    }

    private static List<Object> hashes(ResponseEntity<Map<String, Object>> response) {
        return content(response).stream().map(row -> row.get("hashValue")).toList();
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

    private void seedRestoredWhitelist(String sha256, Instant createdAt) {
        UUID quarantineId = seedQuarantine(sha256);
        jdbcTemplate.update(
                """
                INSERT INTO hash_whitelist (hash_type, hash_value, reason, added_by, origin_quarantine_id, created_at)
                VALUES ('SHA256', ?, 'restored', ?, ?, ?)
                """,
                sha256, seedAdmin(), quarantineId, Timestamp.from(createdAt));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 기본은_활성_미만료만_최신순이고_PageResponse_모양이다() {
        String older = randomSha256();
        String newer = randomSha256();
        seedWhitelist(older, true, null, T1);
        seedWhitelist(newer, true, Instant.now().plus(1, ChronoUnit.DAYS), T2);
        seedWhitelist(randomSha256(), false, null, T3);
        seedWhitelist(randomSha256(), true, Instant.now().minus(1, ChronoUnit.DAYS), T3);

        ResponseEntity<Map<String, Object>> response = list("");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKeys("content", "page", "size", "totalElements", "totalPages");
        assertThat(response.getBody().get("totalElements")).isEqualTo(2);
        assertThat(hashes(response)).containsExactly(newer, older);
        Map<String, Object> first = content(response).get(0);
        assertThat(first.get("origin")).isEqualTo("PREREGISTERED");
        assertThat(first.get("isActive")).isEqualTo(true);
        assertThat(((Map<String, Object>) first.get("addedBy")).get("username")).asString().startsWith("seed-");
    }

    @Test
    void includeExpired면_만료된_항목도_나온다() {
        String expired = randomSha256();
        seedWhitelist(expired, true, Instant.now().minus(1, ChronoUnit.DAYS), T1);

        assertThat(hashes(list(""))).isEmpty();
        assertThat(hashes(list("?includeExpired=true"))).containsExactly(expired);
    }

    @Test
    void isActive_false면_비활성만_나온다() {
        String inactive = randomSha256();
        seedWhitelist(inactive, false, null, T1);
        seedWhitelist(randomSha256(), true, null, T2);

        assertThat(hashes(list("?isActive=false"))).containsExactly(inactive);
    }

    @Test
    void 복원으로_들어온_항목은_RESTORED이고_origin으로_거를_수_있다() {
        String restored = randomSha256();
        String preregistered = randomSha256();
        seedRestoredWhitelist(restored, T1);
        seedWhitelist(preregistered, true, null, T2);

        ResponseEntity<Map<String, Object>> response = list("?origin=RESTORED");

        assertThat(hashes(response)).containsExactly(restored);
        assertThat(content(response).get(0).get("origin")).isEqualTo("RESTORED");
        assertThat(content(response).get(0).get("originQuarantineId")).isNotNull();
        assertThat(hashes(list("?origin=PREREGISTERED"))).containsExactly(preregistered);
    }

    @Test
    void hashValue는_앞부분_일치로_찾고_대소문자를_가리지_않는다() {
        String target = "abcdef" + randomSha256().substring(6);
        seedWhitelist(target, true, null, T1);
        seedWhitelist("0" + randomSha256().substring(1), true, null, T2);

        assertThat(hashes(list("?hashValue=ABCDEF"))).containsExactly(target);
    }

    @Test
    void hashValue의_LIKE_특수문자는_글자_그대로_찾는다() {
        seedWhitelist(randomSha256(), true, null, T1);

        assertThat(hashes(list("?hashValue=%25"))).isEmpty();
        assertThat(hashes(list("?hashValue=_"))).isEmpty();
    }

    @Test
    void 페이지를_나눠서_내려준다() {
        String a = randomSha256();
        String b = randomSha256();
        String c = randomSha256();
        seedWhitelist(a, true, null, T1);
        seedWhitelist(b, true, null, T2);
        seedWhitelist(c, true, null, T3);

        ResponseEntity<Map<String, Object>> response = list("?page=1&size=2");

        assertThat(hashes(response)).containsExactly(a);
        assertThat(response.getBody().get("totalElements")).isEqualTo(3);
        assertThat(response.getBody().get("totalPages")).isEqualTo(2);
    }

    @Test
    void 잘못된_page_size_origin은_400() {
        assertThat(list("?page=-1").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(list("?size=0").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(list("?size=201").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(list("?origin=MANUAL").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 토큰이_없으면_401() {
        ResponseEntity<String> response = restTemplate.getForEntity(URL, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
