package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 위젯끼리 숫자가 맞는지(이슈 #82 완료 기준). 고정 시드로 이벤트 수백 건을 만들고, 같은 기간에서
 * 일별 차단(SOURCE·RULE)의 합, 차단 상위 PC의 합, 시드로 센 차단 수가 모두 같은지 본다.
 * summary(#79)는 아직 없어 시드에서 직접 센 값과 비교한다.
 */
@SuppressWarnings("unchecked")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfiguration.class, AdminApiClient.class})
class DashboardWidgetEqualityIntegrationTest {

    private static final String[] SEVERITIES = {"LOW", "MEDIUM", "HIGH", "CRITICAL"};
    private static final int DAYS = 30;

    @Autowired
    private AdminApiClient adminApi;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static Class<Map<String, Object>> mapType() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }

    private Map<String, Object> get(String url) {
        ResponseEntity<Map<String, Object>> response = adminApi.getForEntity(url, mapType());
        assertThat(response.getStatusCode()).as(url + " → " + response.getBody()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static long sumDaily(Map<String, Object> daily, List<String> keys) {
        long total = 0;
        for (Map<String, Object> day : (List<Map<String, Object>>) daily.get("days")) {
            Map<String, Object> counts = (Map<String, Object>) day.get("counts");
            for (String key : keys) {
                total += ((Number) counts.getOrDefault(key, 0)).longValue();
            }
        }
        return total;
    }

    @Test
    void 같은_기간이면_일별_차단_두_보기와_차단_상위_PC의_합이_차단_수와_같다() {
        DashboardSeed seed = new DashboardSeed(jdbcTemplate);
        Random random = new Random(20261117L);
        List<UUID> agents = List.of(seed.agent("EQ-1"), seed.agent("EQ-2"), seed.agent("EQ-3"), seed.agent("EQ-4"));
        List<String> hosts = List.of("a.com", "b.com", "c.com");
        long blocked = 0;
        long malicious = 0;
        long failClose = 0;
        long blacklist = 0;

        for (int i = 0; i < 300; i++) {
            UUID agent = agents.get(random.nextInt(agents.size()));
            String host = hosts.get(random.nextInt(hosts.size()));
            Instant at = seed.at(random.nextInt(DAYS * 24)).plus(random.nextInt(60), ChronoUnit.MINUTES);
            switch (random.nextInt(6)) {
                case 0 -> {
                    seed.failClose(agent, host, at);
                    blocked++;
                    failClose++;
                }
                case 1 -> {
                    seed.blacklistBlock(agent, host, at, SEVERITIES[random.nextInt(4)]);
                    blocked++;
                    malicious++;
                    blacklist++;
                }
                case 2, 3 -> {
                    // 한 분석 안에서 룰 이름은 겹치지 않는다(uq_analysis_matches).
                    int ruleCount = 1 + random.nextInt(3);
                    int firstRule = random.nextInt(9);
                    String[][] rules = new String[ruleCount][];
                    for (int r = 0; r < ruleCount; r++) {
                        rules[r] = new String[]{"EQ_Rule_" + (firstRule + r), SEVERITIES[random.nextInt(4)]};
                    }
                    seed.engineBlock(agent, host, at, rules);
                    blocked++;
                    malicious++;
                }
                case 4 -> {
                    seed.event(agent, DashboardSeed.randomSha256(), host, "BLOCKED", "SIMILARITY", at);
                    blocked++;
                }
                default -> seed.released(agent, host, at);
            }
        }

        String period = "from=" + seed.windowStart() + "&to=" + seed.windowStart().plus(DAYS, ChronoUnit.DAYS);
        Map<String, Object> bySource = get("/api/admin/dashboard/daily-blocks?stackBy=SOURCE&" + period);
        Map<String, Object> byRule = get("/api/admin/dashboard/daily-blocks?stackBy=RULE&" + period);
        List<Map<String, Object>> topAgents =
                (List<Map<String, Object>>) get("/api/admin/dashboard/top-sources?by=AGENT&size=1000&" + period)
                        .get("content");
        List<Map<String, Object>> topHosts =
                (List<Map<String, Object>>) get("/api/admin/dashboard/top-sources?by=HOST&size=1000&" + period)
                        .get("content");
        List<Map<String, Object>> threats =
                (List<Map<String, Object>>) get("/api/admin/dashboard/threats?size=100&" + period).get("content");

        assertThat(sumDaily(bySource, (List<String>) bySource.get("keys"))).isEqualTo(blocked);
        assertThat(sumDaily(byRule, (List<String>) byRule.get("keys"))).isEqualTo(blocked);
        assertThat(topAgents.stream().mapToLong(r -> ((Number) r.get("blockCount")).longValue()).sum())
                .isEqualTo(blocked);
        assertThat(topHosts.stream().mapToLong(r -> ((Number) r.get("blockCount")).longValue()).sum())
                .isEqualTo(blocked);
        assertThat(sumDaily(bySource, List.of("ENGINE", "CACHE", "BLACKLIST"))).isEqualTo(malicious);
        assertThat(sumDaily(bySource, List.of("FAIL_CLOSE"))).isEqualTo(failClose);
        assertThat(sumDaily(byRule, List.of("FAIL_CLOSE"))).isEqualTo(failClose);
        assertThat(sumDaily(byRule, List.of("BLACKLIST"))).isEqualTo(blacklist);
        Map<String, Object> blacklistRow = threats.stream()
                .filter(r -> "BLACKLIST".equals(r.get("kind"))).findFirst().orElseThrow();
        assertThat(((Number) blacklistRow.get("blockCount")).longValue()).isEqualTo(blacklist);
    }

    @Test
    void 확인_안_한_높은_심각도_차단_수는_이벤트_목록_필터_결과_수와_같다() {
        DashboardSeed seed = new DashboardSeed(jdbcTemplate);
        UUID agent = seed.agent("EQ-ACK");
        Instant now = Instant.now();
        seed.engineBlock(agent, "a.com", now, new String[]{"R", "HIGH"});
        seed.blacklistBlock(agent, "a.com", now, "CRITICAL");
        UUID acked = seed.blacklistBlock(agent, "a.com", now, "HIGH");
        seed.acknowledge(acked);
        seed.engineBlock(agent, "a.com", now, new String[]{"R", "MEDIUM"});
        seed.failClose(agent, "a.com", now);

        Object badge = get("/api/admin/dashboard/unacknowledged-blocks?size=1").get("totalElements");
        Object list = get("/api/admin/download-events?decision=BLOCKED&minSeverity=HIGH&acknowledged=false&size=1")
                .get("totalElements");

        assertThat(list).isEqualTo(badge);
    }
}
