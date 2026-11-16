package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/**
 * 다운로드 이벤트 목록의 대시보드용 확장(이슈 #82). 필드 추가와 필터 추가만 하고 기존 요청 결과는 그대로다.
 * 심각도·대표 룰은 차단(BLOCKED)일 때만 채우고, 확인 여부는 차단(BLOCKED·FAIL_CLOSE)만 true가 될 수 있다.
 */
@SuppressWarnings("unchecked")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfiguration.class})
class DownloadEventListDashboardFieldsRestIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private DashboardSeed seed;
    private UUID agent;

    @BeforeEach
    void setUp() {
        seed = new DashboardSeed(jdbcTemplate);
        agent = seed.agent("LIST-PC");
    }

    private List<Map<String, Object>> list(String query) {
        ResponseEntity<Map<String, Object>> response = restTemplate.getForEntity(
                "/api/admin/download-events?size=200&agentId=" + agent + "&" + query,
                (Class<Map<String, Object>>) (Class<?>) Map.class);
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) response.getBody().get("content");
    }

    private Map<String, Object> only(UUID eventId) {
        return list("").stream().filter(e -> eventId.toString().equals(e.get("eventId"))).findFirst().orElseThrow();
    }

    @Test
    void 룰_차단은_심각도_대표_룰_나머지_룰_수를_준다() {
        UUID id = seed.engineBlock(agent, "a.com", seed.at(1),
                new String[]{"Rule_B", "HIGH"}, new String[]{"Rule_A", "HIGH"}, new String[]{"Rule_C", "LOW"});

        assertThat(only(id)).containsEntry("severity", "HIGH").containsEntry("topRuleName", "Rule_A")
                .containsEntry("extraRuleCount", 2).containsEntry("acknowledged", false);
    }

    @Test
    void 블랙리스트_차단은_블랙리스트_심각도이고_룰은_없다() {
        UUID id = seed.blacklistBlock(agent, "a.com", seed.at(1), "CRITICAL");

        assertThat(only(id)).containsEntry("severity", "CRITICAL").containsEntry("topRuleName", null)
                .containsEntry("extraRuleCount", 0);
    }

    @Test
    void 차단이_아니거나_FAIL_CLOSE면_심각도와_룰이_비어_있다() {
        String sha = DashboardSeed.randomSha256();
        seed.analysis(sha, new String[]{"Rule_Low", "LOW"});
        UUID released = seed.event(agent, sha, "a.com", "RELEASED", "ENGINE", seed.at(1));
        UUID failClose = seed.failClose(agent, "a.com", seed.at(2));

        assertThat(only(released)).containsEntry("severity", null).containsEntry("topRuleName", null)
                .containsEntry("extraRuleCount", 0).containsEntry("acknowledged", false);
        assertThat(only(failClose)).containsEntry("severity", null).containsEntry("topRuleName", null);
    }

    @Test
    void decision을_여러_번_받는다() {
        seed.engineBlock(agent, "a.com", seed.at(1), new String[]{"R", "HIGH"});
        seed.failClose(agent, "a.com", seed.at(2));
        seed.released(agent, "a.com", seed.at(3));

        assertThat(list("decision=BLOCKED&decision=FAIL_CLOSE")).extracting(e -> e.get("decision"))
                .containsExactly("FAIL_CLOSE", "BLOCKED");
        assertThat(list("decision=RELEASED")).hasSize(1);
    }

    @Test
    void minSeverity와_acknowledged로_거른다() {
        UUID high = seed.engineBlock(agent, "a.com", seed.at(1), new String[]{"R", "HIGH"});
        UUID critical = seed.blacklistBlock(agent, "a.com", seed.at(2), "CRITICAL");
        seed.engineBlock(agent, "a.com", seed.at(3), new String[]{"R", "MEDIUM"});
        seed.failClose(agent, "a.com", seed.at(4));
        seed.acknowledge(critical);

        assertThat(list("minSeverity=HIGH")).extracting(e -> e.get("eventId"))
                .containsExactlyInAnyOrder(high.toString(), critical.toString());
        assertThat(list("minSeverity=HIGH&acknowledged=false")).extracting(e -> e.get("eventId"))
                .containsExactly(high.toString());
        assertThat(list("acknowledged=true")).extracting(e -> e.get("eventId"))
                .containsExactly(critical.toString());
        assertThat(only(critical)).containsEntry("acknowledged", true);
    }

    @Test
    void 잘못된_minSeverity와_acknowledged와_decision은_400() {
        for (String query : List.of("minSeverity=SEVERE", "acknowledged=maybe", "decision=BLOCKED&decision=NOPE")) {
            ResponseEntity<String> response =
                    restTemplate.getForEntity("/api/admin/download-events?" + query, String.class);
            assertThat(response.getStatusCode()).as(query).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }
}
