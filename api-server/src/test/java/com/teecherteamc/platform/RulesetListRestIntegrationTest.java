package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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

// 로그인이 필요한 API라 AdminApiClient로 요청한다. v0(빈 기본)·v1(시드)은 마이그레이션이 만든다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfiguration.class, AdminApiClient.class})
class RulesetListRestIntegrationTest {

    private static final String URL = "/api/admin/rulesets";

    @Autowired
    private AdminApiClient adminClient;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 시드(v0, v1) 외의 버전은 이 테스트가 만든 것이므로 앞뒤로 지운다. yara_rules는 CASCADE로 같이 지워진다.
    @BeforeEach
    @AfterEach
    void 시드_외_버전을_지운다() {
        jdbcTemplate.update("DELETE FROM yara_rulesets WHERE rulesets_version > 1");
    }

    private void seedRuleset(int version, int ruleCount) {
        jdbcTemplate.update(
                "INSERT INTO yara_rulesets (rulesets_version, rule_count, is_active, source) VALUES (?, ?, false, 'rule x {}')",
                version, ruleCount);
    }

    private void seedRule(int version, String name, boolean enabled) {
        jdbcTemplate.update(
                "INSERT INTO yara_rules (rule_id, rulesets_version, rule_name, severity, enabled) VALUES (?, ?, ?, 'HIGH', ?)",
                UUID.randomUUID(), version, name, enabled);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map<String, Object>> get(String url) {
        return adminClient.getForEntity(url, (Class<Map<String, Object>>) (Class<?>) Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> content(ResponseEntity<Map<String, Object>> response) {
        return (List<Map<String, Object>>) response.getBody().get("content");
    }

    @Test
    void 목록은_v0을_빼고_최신_버전부터_준다() {
        seedRuleset(2, 3);
        seedRule(2, "A_Rule", true);
        seedRule(2, "B_Rule", false);
        seedRule(2, "C_Rule", true);

        ResponseEntity<Map<String, Object>> response = get(URL);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> content = content(response);
        assertThat(content).hasSize(2);
        assertThat(content.get(0).get("version")).isEqualTo(2);
        assertThat(content.get(0).get("ruleCount")).isEqualTo(3);
        assertThat(content.get(0).get("disabledRuleCount")).isEqualTo(1);
        assertThat(content.get(0).get("isActive")).isEqualTo(false);
        assertThat(content.get(0).get("activatedAt")).isNull();
        assertThat(content.get(1).get("version")).isEqualTo(1);
        assertThat(content.get(1).get("isActive")).isEqualTo(true);
        assertThat(content.get(1).get("disabledRuleCount")).isEqualTo(0);
        assertThat(response.getBody().get("totalElements")).isEqualTo(2);
    }

    @Test
    void 룰_목록은_이름순이고_사용_여부를_준다() {
        ResponseEntity<Map<String, Object>> response = get(URL + "/1/rules");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> content = content(response);
        assertThat(content).extracting(row -> row.get("ruleName"))
                .containsExactly("EICAR_Test_File", "Teecher_Demo_Fake_Malware");
        assertThat(content).extracting(row -> row.get("severity")).containsOnly("HIGH");
        assertThat(content).extracting(row -> row.get("enabled")).containsOnly(true);
        assertThat(content.get(0).get("ruleId")).isNotNull();
    }

    @Test
    void 없는_버전의_룰_목록은_404() {
        assertThat(get(URL + "/999/rules").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void v0의_룰_목록은_404() {
        assertThat(get(URL + "/0/rules").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void size가_범위를_벗어나면_400() {
        assertThat(get(URL + "?size=0").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(URL + "?size=101").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 로그인_없이_요청하면_401() {
        assertThat(restTemplate.getForEntity(URL, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
