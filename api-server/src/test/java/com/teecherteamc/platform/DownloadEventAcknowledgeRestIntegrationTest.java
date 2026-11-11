package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 차단 이벤트 확인 처리(POST /api/admin/download-events/{eventId}/acknowledge)와
 * 확인 안 한 높은 심각도 차단 목록(GET /api/admin/dashboard/unacknowledged-blocks). 이슈 #81.
 *
 * <p>심각도·대표 룰·확인 여부는 download_event_threats 뷰(V7)가 계산한다. 여기서는 그 뷰를 쓰는 API의 계약을 본다.
 * 다른 테스트 클래스와 DB를 같이 쓰므로 목록 검증은 이 테스트가 만든 이벤트로만 거른다.
 */
@SuppressWarnings("unchecked")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfiguration.class, AdminApiClient.class})
class DownloadEventAcknowledgeRestIntegrationTest {

    private static final String LIST_URL = "/api/admin/dashboard/unacknowledged-blocks";

    @Autowired
    private AdminApiClient adminApi;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID agentId;
    private String hostname;

    @BeforeEach
    void setUp() {
        // DB를 다른 테스트와 같이 쓰므로, 목록 검증은 이 테스트가 만든 PC(hostname)의 행만 본다.
        hostname = "ACK-PC-" + UUID.randomUUID().toString().substring(0, 8);
        UUID deviceId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO devices (device_id, hostname, os_platform) VALUES (?, ?, 'WINDOWS')",
                deviceId, hostname);
        agentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO agents (agent_id, device_id, agent_version, enrollment_token_hash) VALUES (?, ?, '0.0.1', ?)",
                agentId, deviceId, "hash-" + agentId);
    }

    private static Class<Map<String, Object>> mapType() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }

    private static String ackUrl(UUID eventId) {
        return "/api/admin/download-events/" + eventId + "/acknowledge";
    }

    private static String randomSha256() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }

    private UUID seedEvent(String sha256, String decision, String decisionSource, Instant createdAt) {
        if (sha256 != null) {
            jdbcTemplate.update(
                    "INSERT INTO file_verdicts (sha256, verdict, verdict_source) VALUES (?, 'MALICIOUS', 'ENGINE') "
                            + "ON CONFLICT DO NOTHING",
                    sha256);
        }
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO download_events
                    (event_id, agent_id, sha256, request_host, url, filename, pipeline_status,
                     decision, decision_source, held_at, decided_at, created_at)
                VALUES (?, ?, ?, 'example.com', 'https://example.com/f', ?, 'COMPLETED', ?, ?, now(), now(), ?)
                """,
                eventId, agentId, sha256, "file-" + eventId + ".exe", decision, decisionSource,
                Timestamp.from(createdAt));
        return eventId;
    }

    /** ENGINE 차단 + 최신 SUCCESS 분석에 룰을 건다. rules는 {이름, 심각도} 쌍. */
    private UUID seedEngineBlock(Instant createdAt, String[]... rules) {
        String sha256 = randomSha256();
        UUID eventId = seedEvent(sha256, "BLOCKED", "ENGINE", createdAt);
        UUID analysisId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO analyses
                    (analysis_id, sha256, rulesets_version, engine_version, status, verdict, duration_ms)
                VALUES (?, ?, 0, '1.0.0', 'SUCCESS', 'MALICIOUS', 100)
                """,
                analysisId, sha256);
        for (String[] rule : rules) {
            jdbcTemplate.update(
                    "INSERT INTO analysis_matches (match_id, analysis_id, rule_name, severity) VALUES (?, ?, ?, ?)",
                    UUID.randomUUID(), analysisId, rule[0], rule[1]);
        }
        return eventId;
    }

    private UUID seedBlacklistBlock(Instant createdAt, String severity) {
        String sha256 = randomSha256();
        jdbcTemplate.update(
                "INSERT INTO hash_blacklist (hash_type, hash_value, reason, severity) VALUES ('SHA256', ?, 'bad', ?)",
                sha256, severity);
        return seedEvent(sha256, "BLOCKED", "BLACKLIST", createdAt);
    }

    private int ackRows(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM download_event_acknowledgements WHERE event_id = ?", Integer.class, eventId);
    }

    private int auditRows(UUID eventId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM audit_logs
                WHERE action = 'DOWNLOAD_EVENT_ACKNOWLEDGE' AND target_type = 'DOWNLOAD_EVENT' AND target_id = ?
                """,
                Integer.class, eventId.toString());
    }

    private List<Map<String, Object>> listContent() {
        ResponseEntity<Map<String, Object>> response = adminApi.getForEntity(LIST_URL + "?size=200", mapType());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((List<Map<String, Object>>) response.getBody().get("content")).stream()
                .filter(item -> hostname.equals(item.get("hostname")))
                .toList();
    }

    @Test
    void 차단_이벤트를_처음_확인하면_200과_확인한_사람_시각을_주고_감사_로그를_한_번_남긴다() {
        UUID eventId = seedEngineBlock(Instant.now(), new String[]{"Rule_A", "HIGH"});

        ResponseEntity<Map<String, Object>> response = adminApi.postForEntity(ackUrl(eventId), null, mapType());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("eventId", eventId.toString())
                .containsEntry("acknowledgedBy", adminApi.username())
                .containsKey("acknowledgedAt");
        assertThat(ackRows(eventId)).isEqualTo(1);
        assertThat(auditRows(eventId)).isEqualTo(1);
    }

    @Test
    void 이미_확인한_이벤트는_처음_확인한_사람_시각을_그대로_주고_감사_로그를_더_남기지_않는다() {
        UUID eventId = seedEngineBlock(Instant.now(), new String[]{"Rule_A", "HIGH"});
        Map<String, Object> first = adminApi.postForEntity(ackUrl(eventId), null, mapType()).getBody();

        ResponseEntity<Map<String, Object>> second = adminApi.postForEntity(ackUrl(eventId), null, mapType());

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).isEqualTo(first);
        assertThat(ackRows(eventId)).isEqualTo(1);
        assertThat(auditRows(eventId)).isEqualTo(1);
    }

    @Test
    void 동시에_확인해도_확인_행과_감사_로그는_하나씩이고_모두_같은_결과를_받는다() throws Exception {
        UUID eventId = seedEngineBlock(Instant.now(), new String[]{"Rule_A", "CRITICAL"});
        adminApi.accessToken();   // 로그인을 미리 해 둬서 스레드끼리 로그인이 겹치지 않게 한다
        int threads = 6;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            Callable<ResponseEntity<Map<String, Object>>> call = () -> {
                start.await();
                return adminApi.postForEntity(ackUrl(eventId), null, mapType());
            };
            List<Future<ResponseEntity<Map<String, Object>>>> futures =
                    java.util.stream.IntStream.range(0, threads).mapToObj(i -> pool.submit(call)).toList();
            start.countDown();
            List<ResponseEntity<Map<String, Object>>> responses = new java.util.ArrayList<>();
            for (Future<ResponseEntity<Map<String, Object>>> future : futures) {
                responses.add(future.get());
            }

            assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK));
            assertThat(responses.stream().map(r -> r.getBody().get("acknowledgedAt")).distinct()).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(ackRows(eventId)).isEqualTo(1);
        assertThat(auditRows(eventId)).isEqualTo(1);
    }

    @Test
    void 장애_차단도_차단이라_확인할_수_있다() {
        UUID eventId = seedEvent(null, "FAIL_CLOSE", null, Instant.now());

        assertThat(adminApi.postForEntity(ackUrl(eventId), null, mapType()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void 없는_이벤트는_404이고_잘못된_id는_400이다() {
        ResponseEntity<Map<String, Object>> missing =
                adminApi.postForEntity(ackUrl(UUID.randomUUID()), null, mapType());
        ResponseEntity<Map<String, Object>> invalid =
                adminApi.postForEntity("/api/admin/download-events/not-a-uuid/acknowledge", null, mapType());

        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getBody()).containsEntry("code", "NOT_FOUND");
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 차단이_아닌_이벤트는_409이고_아무것도_남기지_않는다() {
        UUID eventId = seedEvent(randomSha256(), "RELEASED", "ENGINE", Instant.now());

        ResponseEntity<Map<String, Object>> response = adminApi.postForEntity(ackUrl(eventId), null, mapType());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("code", "CONFLICT");
        assertThat(ackRows(eventId)).isZero();
        assertThat(auditRows(eventId)).isZero();
    }

    @Test
    void 목록은_미확인_HIGH_CRITICAL_차단만_최신순으로_주고_사유를_RULE_BLACKLIST로_나눈다() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        UUID high = seedEngineBlock(now.minus(3, ChronoUnit.HOURS),
                new String[]{"Zeta_Rule", "HIGH"}, new String[]{"Alpha_Rule", "HIGH"}, new String[]{"Low_Rule", "LOW"});
        UUID critical = seedBlacklistBlock(now.minus(1, ChronoUnit.HOURS), "CRITICAL");
        seedEngineBlock(now.minus(2, ChronoUnit.HOURS), new String[]{"Medium_Rule", "MEDIUM"});
        UUID acknowledged = seedEngineBlock(now.minus(4, ChronoUnit.HOURS), new String[]{"Done_Rule", "HIGH"});
        jdbcTemplate.update("INSERT INTO download_event_acknowledgements (event_id, acknowledged_by) VALUES (?, ?)",
                acknowledged, adminApi.adminId());
        seedEvent(null, "FAIL_CLOSE", null, now);
        // 통과한 파일은 룰이 걸렸어도 차단이 아니므로 빠진다
        UUID released = seedEvent(randomSha256(), "RELEASED", "ENGINE", now);

        List<Map<String, Object>> content = listContent();

        assertThat(content).extracting(item -> item.get("eventId"))
                .containsExactly(critical.toString(), high.toString())
                .doesNotContain(released.toString());
        Map<String, Object> blacklist = content.get(0);
        assertThat(blacklist)
                .containsEntry("reasonType", "BLACKLIST")
                .containsEntry("severity", "CRITICAL")
                .containsEntry("extraRuleCount", 0)
                .containsEntry("agentId", agentId.toString())
                .containsKeys("createdAt", "filename");
        assertThat(blacklist.get("topRuleName")).isNull();
        assertThat(content.get(1))
                .containsEntry("reasonType", "RULE")
                .containsEntry("severity", "HIGH")
                .containsEntry("topRuleName", "Alpha_Rule")
                .containsEntry("extraRuleCount", 2);
    }

    @Test
    void 확인하면_다음_조회부터_목록과_전체_건수에서_빠진다() {
        UUID first = seedEngineBlock(Instant.now(), new String[]{"Rule_A", "HIGH"});
        seedEngineBlock(Instant.now(), new String[]{"Rule_B", "CRITICAL"});
        long before = ((Number) adminApi.getForEntity(LIST_URL, mapType()).getBody().get("totalElements")).longValue();

        adminApi.postForEntity(ackUrl(first), null, mapType());

        Map<String, Object> after = adminApi.getForEntity(LIST_URL, mapType()).getBody();
        assertThat(((Number) after.get("totalElements")).longValue()).isEqualTo(before - 1);
        assertThat(listContent()).extracting(item -> item.get("eventId")).doesNotContain(first.toString());
    }

    @Test
    void 목록은_PageResponse이고_page_size가_틀리면_400이다() {
        Map<String, Object> body = adminApi.getForEntity(LIST_URL + "?page=0&size=5", mapType()).getBody();
        assertThat(body).containsKeys("content", "page", "size", "totalElements", "totalPages");
        assertThat(body).containsEntry("size", 5);

        assertThat(adminApi.getForEntity(LIST_URL + "?page=-1", mapType()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(adminApi.getForEntity(LIST_URL + "?size=0", mapType()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(adminApi.getForEntity(LIST_URL + "?size=201", mapType()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
