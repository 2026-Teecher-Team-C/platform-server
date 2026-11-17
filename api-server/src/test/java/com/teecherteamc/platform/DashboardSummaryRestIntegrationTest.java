package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * 대시보드 상단 카운터(GET /api/admin/dashboard/summary)와 엔진 상태 API 응답 모양.
 * 계산 기준은 콘솔 mock(console/src/mocks/dashboard/aggregate.ts의 computeSummary)과 같다.
 */
@SuppressWarnings("unchecked")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfiguration.class, AdminApiClient.class})
class DashboardSummaryRestIntegrationTest {

    @Autowired
    private AdminApiClient adminApi;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private DashboardSeed seed;
    private UUID agentA;
    private UUID agentB;

    @BeforeEach
    void setUp() {
        seed = new DashboardSeed(jdbcTemplate);
        agentA = seed.agent("PC-A");
        agentB = seed.agent("PC-B");
    }

    private static Class<Map<String, Object>> mapType() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }

    private String period(long days) {
        return "from=" + seed.windowStart() + "&to=" + seed.windowStart().plus(days, ChronoUnit.DAYS);
    }

    private Map<String, Object> get(String url) {
        ResponseEntity<Map<String, Object>> response = adminApi.getForEntity(url, mapType());
        assertThat(response.getStatusCode()).as(url + " → " + response.getBody()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private HttpStatus status(String url) {
        return HttpStatus.valueOf(adminApi.getForEntity(url, mapType()).getStatusCode().value());
    }

    @Test
    void 기간_안의_차단_악성_출처_FAIL_CLOSE_영향_PC_검사_수를_센다() {
        seed.engineBlock(agentA, "a.com", seed.at(1), new String[]{"Rule_X", "HIGH"});
        seed.event(agentA, DashboardSeed.randomSha256(), "a.com", "BLOCKED", "CACHE", seed.at(2));
        seed.blacklistBlock(agentB, "b.com", seed.at(3), "HIGH");
        // TLSH 유사도 차단: 차단에는 들어가지만 malicious(룰·판정 이력·블랙리스트)에는 안 들어간다.
        seed.event(agentB, DashboardSeed.randomSha256(), "b.com", "BLOCKED", "SIMILARITY", seed.at(4));
        seed.failClose(agentA, "a.com", seed.at(5));
        seed.released(agentA, "a.com", seed.at(6));
        seed.event(agentA, null, "bypass.com", "BYPASSED", null, seed.at(7));
        // 판정 전(decision NULL)은 검사에 넣지 않는다.
        jdbcTemplate.update(
                """
                INSERT INTO download_events (event_id, agent_id, request_host, url, filename, pipeline_status,
                                             held_at, created_at)
                VALUES (?, ?, 'pending.com', 'https://pending.com/f', 'f.exe', 'ANALYZING', ?, ?)
                """,
                UUID.randomUUID(), agentA, Timestamp.from(seed.at(8)), Timestamp.from(seed.at(8)));
        // 기간 끝(to)은 포함하지 않는다.
        seed.failClose(agentB, "b.com", seed.at(24));

        Map<String, Object> body = get("/api/admin/dashboard/summary?" + period(1));

        assertThat(body).containsEntry("blocked", 5)
                .containsEntry("failClose", 1)
                .containsEntry("affectedAgents", 2)
                .containsEntry("inspected", 6)
                .containsEntry("blockedPreviousPeriod", 0);
        assertThat((Map<String, Object>) body.get("malicious")).containsExactlyInAnyOrderEntriesOf(
                Map.of("total", 3, "engine", 1, "cache", 1, "blacklist", 1));
        assertThat(((Number) body.get("blockRate")).doubleValue()).isEqualTo(5.0 / 6);
        assertThat((Map<String, Object>) body.get("period")).containsExactlyInAnyOrderEntriesOf(Map.of(
                "from", seed.windowStart().toString(),
                "to", seed.windowStart().plus(1, ChronoUnit.DAYS).toString()));
    }

    @Test
    void 직전_기간은_바로_앞_같은_길이_기간의_차단_수다() {
        seed.failClose(agentA, "a.com", seed.at(-1));
        seed.failClose(agentA, "a.com", seed.at(-48));
        // 2일 기간이면 직전 기간은 [-48h, 0) — -49h는 빠진다.
        seed.failClose(agentA, "a.com", seed.at(-49));
        seed.released(agentA, "a.com", seed.at(-2));
        seed.failClose(agentA, "a.com", seed.at(1));

        Map<String, Object> body = get("/api/admin/dashboard/summary?" + period(2));

        assertThat(body).containsEntry("blocked", 1).containsEntry("blockedPreviousPeriod", 2);
    }

    @Test
    void 이벤트가_0건이면_모든_값이_0이고_blockRate도_0이다() {
        Map<String, Object> body = get("/api/admin/dashboard/summary?" + period(1));

        assertThat(body).containsEntry("blocked", 0)
                .containsEntry("blockedPreviousPeriod", 0)
                .containsEntry("failClose", 0)
                .containsEntry("affectedAgents", 0)
                .containsEntry("inspected", 0);
        assertThat((Map<String, Object>) body.get("malicious")).containsExactlyInAnyOrderEntriesOf(
                Map.of("total", 0, "engine", 0, "cache", 0, "blacklist", 0));
        assertThat(((Number) body.get("blockRate")).doubleValue()).isZero();
    }

    @Test
    void 전체_에이전트_수는_REVOKED를_뺀다() {
        long before = ((Number) get("/api/admin/dashboard/summary?" + period(1)).get("totalAgents")).longValue();
        UUID revoked = seed.agent("PC-REVOKED");
        jdbcTemplate.update("UPDATE agents SET status = 'REVOKED' WHERE agent_id = ?", revoked);
        UUID inactive = seed.agent("PC-INACTIVE");
        jdbcTemplate.update("UPDATE agents SET status = 'INACTIVE' WHERE agent_id = ?", inactive);

        long after = ((Number) get("/api/admin/dashboard/summary?" + period(1)).get("totalAgents")).longValue();

        assertThat(after).isEqualTo(before + 1);
    }

    @Test
    void 기간이_잘못되면_400() {
        String from = seed.windowStart().toString();
        String to91 = seed.windowStart().plus(91, ChronoUnit.DAYS).toString();
        String base = "/api/admin/dashboard/summary?";
        assertThat(status(base + "from=" + from + "&to=" + from)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(base + "from=" + to91 + "&to=" + from)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(base + "from=" + from + "&to=" + to91)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(base + "from=yesterday&to=" + from)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 엔진_상태_응답은_콘솔_EngineStatus와_같은_필드를_가진다() {
        Map<String, Object> body = get("/api/admin/dashboard/engine-status");

        assertThat(body).containsOnlyKeys("state", "since", "lastSuccessAt", "lastFailureAt", "windowMinutes",
                "failCloseSinceDown", "affectedAgentsSinceDown");
        assertThat(body).containsEntry("windowMinutes", 10);
        assertThat(List.of("HEALTHY", "DOWN", "IDLE")).contains((String) body.get("state"));
    }
}
