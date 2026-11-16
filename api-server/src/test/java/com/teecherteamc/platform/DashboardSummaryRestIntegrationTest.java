package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * 위협 대시보드 카운터 요약(GET /api/admin/dashboard/summary). 이슈 #79.
 * 계산 기준은 콘솔 mock(computeSummary)과 같다.
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

    @BeforeEach
    void setUp() {
        seed = new DashboardSeed(jdbcTemplate);
    }

    private static Class<Map<String, Object>> mapType() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }

    private Map<String, Object> summary(Instant from, Instant to) {
        String url = "/api/admin/dashboard/summary?from=" + from + "&to=" + to;
        ResponseEntity<Map<String, Object>> response = adminApi.getForEntity(url, mapType());
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    @Test
    void 차단_악성_출처별_FAIL_CLOSE_영향_PC_검사_수와_차단율을_센다() {
        UUID a = seed.agent("SUM-A");
        UUID b = seed.agent("SUM-B");
        seed.engineBlock(a, "a.com", seed.at(1), new String[]{"R", "HIGH"});
        seed.event(a, DashboardSeed.randomSha256(), "a.com", "BLOCKED", "CACHE", seed.at(2));
        seed.blacklistBlock(b, "b.com", seed.at(3), "HIGH");
        seed.event(b, DashboardSeed.randomSha256(), "b.com", "BLOCKED", "SIMILARITY", seed.at(4));
        seed.failClose(a, "a.com", seed.at(5));
        seed.released(b, "b.com", seed.at(6));
        seed.released(b, "b.com", seed.at(7));
        seed.event(b, null, "b.com", "BYPASSED", "POLICY", seed.at(8));
        seed.event(b, null, "b.com", null, null, seed.at(9));
        // 기간 밖
        seed.failClose(a, "a.com", seed.at(-1));
        seed.failClose(a, "a.com", seed.at(-2));

        Map<String, Object> body = summary(seed.windowStart(), seed.at(24));

        assertThat((Map<String, Object>) body.get("period"))
                .containsEntry("from", seed.windowStart().toString()).containsEntry("to", seed.at(24).toString());
        assertThat(body).containsEntry("blocked", 5).containsEntry("failClose", 1)
                .containsEntry("affectedAgents", 2).containsEntry("inspected", 7)
                .containsEntry("blockedPreviousPeriod", 2);
        assertThat((Map<String, Object>) body.get("malicious")).containsEntry("total", 3)
                .containsEntry("engine", 1).containsEntry("cache", 1).containsEntry("blacklist", 1);
        assertThat(((Number) body.get("blockRate")).doubleValue()).isEqualTo(5.0 / 7);
    }

    @Test
    void 이벤트가_없으면_모두_0이고_차단율도_0이다() {
        Map<String, Object> body = summary(seed.windowStart(), seed.at(24));

        assertThat(body).containsEntry("blocked", 0).containsEntry("inspected", 0).containsEntry("affectedAgents", 0);
        assertThat(((Number) body.get("blockRate")).doubleValue()).isZero();
    }

    @Test
    void 전체_에이전트_수는_REVOKED를_뺀다() {
        Instant now = Instant.now();
        long before = ((Number) summary(now.minus(1, ChronoUnit.DAYS), now).get("totalAgents")).longValue();
        seed.agent("SUM-ACTIVE");
        UUID revoked = seed.agent("SUM-REVOKED");
        jdbcTemplate.update("UPDATE agents SET status = 'REVOKED' WHERE agent_id = ?", revoked);

        long after = ((Number) summary(now.minus(1, ChronoUnit.DAYS), now).get("totalAgents")).longValue();

        assertThat(after - before).isEqualTo(1);
    }

    @Test
    void 기간이_잘못되면_400() {
        String base = "/api/admin/dashboard/summary";
        Instant from = seed.windowStart();
        assertThat(adminApi.getForEntity(base + "?from=" + from + "&to=" + from, String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(adminApi.getForEntity(base + "?from=" + from + "&to=" + from.plus(91, ChronoUnit.DAYS),
                String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
