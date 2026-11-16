package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
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
 * 위협 대시보드 위젯 API: 위협별 현황(threats), 일별 차단(daily-blocks), 차단 상위 PC·도메인(top-sources).
 * 이슈 #80, #82. 계산 기준은 콘솔 mock(console/src/mocks/dashboard/aggregate.ts)과 같다.
 */
@SuppressWarnings("unchecked")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfiguration.class, AdminApiClient.class})
class DashboardWidgetsRestIntegrationTest {

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

    private static List<Map<String, Object>> content(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("content");
    }

    // ---- threats ----

    @Test
    void 위협별_현황은_룰마다_한_줄이고_여러_룰이_걸린_이벤트는_모든_룰_줄에_센다() {
        seed.engineBlock(agentA, "a.com", seed.at(1), new String[]{"Rule_X", "HIGH"}, new String[]{"Rule_Y", "LOW"});
        seed.engineBlock(agentB, "b.com", seed.at(2), new String[]{"Rule_X", "CRITICAL"});
        seed.blacklistBlock(agentA, "a.com", seed.at(3), "MEDIUM");
        seed.blacklistBlock(agentA, "a.com", seed.at(4), "HIGH");
        seed.failClose(agentA, "a.com", seed.at(5));
        seed.released(agentA, "a.com", seed.at(6));

        List<Map<String, Object>> rows = content(get("/api/admin/dashboard/threats?" + period(1)));

        assertThat(rows).extracting(r -> r.get("kind") + ":" + r.get("ruleName"))
                .containsExactly("BLACKLIST:null", "RULE:Rule_X", "RULE:Rule_Y");
        Map<String, Object> blacklist = rows.get(0);
        assertThat(blacklist).containsEntry("severity", "HIGH").containsEntry("blockCount", 2)
                .containsEntry("affectedAgents", 1);
        Map<String, Object> ruleX = rows.get(1);
        assertThat(ruleX).containsEntry("severity", "CRITICAL").containsEntry("blockCount", 2)
                .containsEntry("affectedAgents", 2);
        assertThat(Instant.parse((String) ruleX.get("firstSeenAt"))).isEqualTo(seed.at(1));
        assertThat(Instant.parse((String) ruleX.get("lastSeenAt"))).isEqualTo(seed.at(2));
        assertThat(rows.get(2)).containsEntry("blockCount", 1).containsEntry("severity", "LOW");
    }

    @Test
    void 위협별_현황은_size만큼_마지막_탐지_최신순으로_자른다() {
        for (int i = 0; i < 12; i++) {
            seed.engineBlock(agentA, "a.com", seed.at(i), new String[]{"Rule_" + i, "HIGH"});
        }

        List<Map<String, Object>> defaults = content(get("/api/admin/dashboard/threats?" + period(1)));
        List<Map<String, Object>> three = content(get("/api/admin/dashboard/threats?size=3&" + period(1)));

        assertThat(defaults).hasSize(10);
        assertThat(three).extracting(r -> r.get("ruleName")).containsExactly("Rule_11", "Rule_10", "Rule_9");
    }

    @Test
    void 기간_밖의_차단은_세지_않는다() {
        seed.engineBlock(agentA, "a.com", seed.at(-1), new String[]{"Rule_Before", "HIGH"});
        seed.engineBlock(agentA, "a.com", seed.at(24), new String[]{"Rule_At_To", "HIGH"});

        assertThat(content(get("/api/admin/dashboard/threats?" + period(1)))).isEmpty();
    }

    // ---- daily-blocks ----

    @Test
    void 일별_차단_SOURCE는_출처별로_세고_차단_없는_날도_0으로_채운다() {
        seed.engineBlock(agentA, "a.com", seed.at(1), new String[]{"Rule_X", "HIGH"});
        seed.blacklistBlock(agentA, "a.com", seed.at(2), "HIGH");
        seed.failClose(agentA, "a.com", seed.at(3));
        seed.event(agentA, DashboardSeed.randomSha256(), "a.com", "BLOCKED", "CACHE", seed.at(50));
        seed.released(agentA, "a.com", seed.at(4));

        Map<String, Object> body = get("/api/admin/dashboard/daily-blocks?tz=UTC&stackBy=SOURCE&" + period(3));

        assertThat(body).containsEntry("stackBy", "SOURCE");
        assertThat((List<String>) body.get("keys")).containsExactly("ENGINE", "CACHE", "BLACKLIST", "FAIL_CLOSE");
        List<Map<String, Object>> days = (List<Map<String, Object>>) body.get("days");
        String day0 = seed.windowStart().toString().substring(0, 10);
        assertThat(days).hasSize(3);
        assertThat(days.get(0)).containsEntry("date", day0);
        assertThat((Map<String, Object>) days.get(0).get("counts"))
                .containsExactly(Map.entry("ENGINE", 1), Map.entry("CACHE", 0), Map.entry("BLACKLIST", 1),
                        Map.entry("FAIL_CLOSE", 1));
        assertThat((Map<String, Object>) days.get(1).get("counts")).containsEntry("ENGINE", 0)
                .containsEntry("FAIL_CLOSE", 0);
        assertThat((Map<String, Object>) days.get(2).get("counts")).containsEntry("CACHE", 1);
    }

    @Test
    void 일별_차단_SOURCE는_TLSH_유사도_차단이_있으면_SIMILARITY_칸을_따로_둔다() {
        seed.event(agentA, DashboardSeed.randomSha256(), "a.com", "BLOCKED", "SIMILARITY", seed.at(1));

        Map<String, Object> body = get("/api/admin/dashboard/daily-blocks?tz=UTC&stackBy=SOURCE&" + period(1));

        assertThat((List<String>) body.get("keys"))
                .containsExactly("ENGINE", "CACHE", "BLACKLIST", "SIMILARITY", "FAIL_CLOSE");
        List<Map<String, Object>> days = (List<Map<String, Object>>) body.get("days");
        assertThat((Map<String, Object>) days.get(0).get("counts")).containsEntry("SIMILARITY", 1);
    }

    @Test
    void 일별_차단_RULE은_대표_룰_상위_5개와_BLACKLIST_OTHER_FAIL_CLOSE로_나누고_이벤트는_한_칸에만_센다() {
        for (int rule = 1; rule <= 6; rule++) {
            for (int n = 0; n < 7 - rule; n++) {
                seed.engineBlock(agentA, "a.com", seed.at(1), new String[]{"R" + rule, "HIGH"});
            }
        }
        // 두 룰이 걸린 이벤트: 대표 룰(심각도 높은 R1)에만 센다.
        seed.engineBlock(agentA, "a.com", seed.at(1), new String[]{"R1", "CRITICAL"}, new String[]{"R2", "LOW"});
        seed.blacklistBlock(agentA, "a.com", seed.at(1), "HIGH");
        seed.failClose(agentA, "a.com", seed.at(1));
        seed.event(agentA, DashboardSeed.randomSha256(), "a.com", "BLOCKED", "SIMILARITY", seed.at(1));

        Map<String, Object> body = get("/api/admin/dashboard/daily-blocks?tz=UTC&stackBy=RULE&" + period(1));

        assertThat((List<String>) body.get("keys"))
                .containsExactly("R1", "R2", "R3", "R4", "R5", "BLACKLIST", "OTHER", "FAIL_CLOSE");
        Map<String, Object> counts =
                (Map<String, Object>) ((List<Map<String, Object>>) body.get("days")).get(0).get("counts");
        assertThat(counts).containsEntry("R1", 7).containsEntry("R2", 5).containsEntry("R5", 2)
                .containsEntry("BLACKLIST", 1).containsEntry("FAIL_CLOSE", 1)
                // R6 1건 + SIMILARITY(룰 없음) 1건
                .containsEntry("OTHER", 2);
    }

    @Test
    void 일별_차단은_tz_기준_날짜로_센다_UTC_15시30분은_서울_다음_날이다() {
        seed.engineBlock(agentA, "a.com", seed.at(15).plus(30, ChronoUnit.MINUTES), new String[]{"Rule_X", "HIGH"});

        Map<String, Object> body = get("/api/admin/dashboard/daily-blocks?stackBy=SOURCE&" + period(2));

        List<Map<String, Object>> days = (List<Map<String, Object>>) body.get("days");
        String nextDay = seed.windowStart().plus(1, ChronoUnit.DAYS).toString().substring(0, 10);
        Map<String, Object> nextDayRow = days.stream().filter(d -> nextDay.equals(d.get("date"))).findFirst().orElseThrow();
        assertThat((Map<String, Object>) nextDayRow.get("counts")).containsEntry("ENGINE", 1);
        // 서울 기준 기간 [D 09:00, D+2 09:00)은 D, D+1, D+2 세 날짜에 걸친다.
        assertThat(days).hasSize(3);
    }

    @Test
    void 없는_tz와_잘못된_stackBy는_400() {
        assertThat(status("/api/admin/dashboard/daily-blocks?tz=Mars/Base&stackBy=SOURCE&" + period(1)))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status("/api/admin/dashboard/daily-blocks?stackBy=WEEK&" + period(1)))
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---- top-sources ----

    @Test
    void 차단_상위_PC는_에이전트별_차단_수_내림차순이고_라벨은_호스트명이다() {
        seed.engineBlock(agentA, "a.com", seed.at(1), new String[]{"Rule_X", "HIGH"});
        seed.failClose(agentA, "b.com", seed.at(2));
        seed.blacklistBlock(agentB, "b.com", seed.at(3), "HIGH");
        seed.released(agentB, "b.com", seed.at(4));

        Map<String, Object> body = get("/api/admin/dashboard/top-sources?by=AGENT&" + period(1));

        assertThat(body).containsEntry("by", "AGENT");
        assertThat(content(body)).containsExactly(
                Map.of("key", agentA.toString(), "label", "PC-A", "blockCount", 2),
                Map.of("key", agentB.toString(), "label", "PC-B", "blockCount", 1));
    }

    @Test
    void 차단_상위_도메인은_요청_호스트별이고_size로_자른다() {
        seed.failClose(agentA, "b.com", seed.at(1));
        seed.failClose(agentA, "b.com", seed.at(2));
        seed.failClose(agentB, "a.com", seed.at(3));
        seed.failClose(agentB, "c.com", seed.at(4));

        Map<String, Object> body = get("/api/admin/dashboard/top-sources?by=HOST&size=2&" + period(1));

        assertThat(content(body)).containsExactly(
                Map.of("key", "b.com", "label", "b.com", "blockCount", 2),
                Map.of("key", "a.com", "label", "a.com", "blockCount", 1));
    }

    @Test
    void 잘못된_by와_size는_400() {
        assertThat(status("/api/admin/dashboard/top-sources?by=DEVICE&" + period(1)))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status("/api/admin/dashboard/top-sources?by=AGENT&size=0&" + period(1)))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status("/api/admin/dashboard/threats?size=0&" + period(1))).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---- 공통 기간 ----

    @Test
    void 기간이_잘못되면_400() {
        String from = seed.windowStart().toString();
        String to91 = seed.windowStart().plus(91, ChronoUnit.DAYS).toString();
        for (String path : List.of("threats?", "daily-blocks?stackBy=SOURCE&", "top-sources?by=AGENT&")) {
            String base = "/api/admin/dashboard/" + path;
            assertThat(status(base + "from=" + from + "&to=" + from)).as(path + " from=to").isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(status(base + "from=" + from + "&to=" + to91)).as(path + " 91일").isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(status(base + "from=yesterday&to=" + from)).as(path + " 형식").isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void 기간을_생략하면_최근_7일이다() {
        UUID recent = seed.agent("PC-RECENT-" + UUID.randomUUID().toString().substring(0, 8));
        seed.failClose(recent, "recent.example", Instant.now().minus(6, ChronoUnit.DAYS));
        seed.failClose(recent, "recent.example", Instant.now().minus(8, ChronoUnit.DAYS));

        List<Map<String, Object>> rows = content(get("/api/admin/dashboard/top-sources?by=AGENT&size=1000"));

        assertThat(rows).filteredOn(r -> recent.toString().equals(r.get("key")))
                .singleElement().satisfies(r -> assertThat(r).containsEntry("blockCount", 1));
    }
}
