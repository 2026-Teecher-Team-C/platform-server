package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.teecherteamc.platform.ruleset.application.ActiveRulesetService;
import com.teecherteamc.platform.ruleset.application.UploadRulesetUseCase;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

// 룰 사용 여부 변경. 엔진 적재는 FakeDetectionEngineService가 대신한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@AutoConfigureTestGrpcTransport
@Import({TestcontainersConfiguration.class, AdminApiClient.class})
class YaraRuleToggleRestIntegrationTest {

    private static final String SOURCE = "rule Alpha_Rule { condition: true }\nrule Beta_Rule { condition: false }\n";

    @Autowired
    private AdminApiClient adminClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UploadRulesetUseCase uploadRulesetUseCase;

    @Autowired
    private ActiveRulesetService activeRulesetService;

    @BeforeEach
    @AfterEach
    void 시드_상태로_되돌린다() {
        FakeDetectionEngineService.reset();
        jdbcTemplate.update("DELETE FROM file_verdicts WHERE sha256 LIKE 'ad%'");
        RulesetTestSupport.restoreSeed(jdbcTemplate);
    }

    private int uploadPending() {
        return uploadRulesetUseCase.upload(new UploadRulesetUseCase.Command(
                "v.yar", SOURCE.getBytes(StandardCharsets.UTF_8), adminClient.adminId(), "127.0.0.1")).getVersion();
    }

    private UUID ruleId(int version) {
        return jdbcTemplate.queryForObject("SELECT rule_id FROM yara_rules WHERE rulesets_version = ? "
                + "ORDER BY rule_name LIMIT 1", UUID.class, version);
    }

    private String seedEngineVerdict() {
        String sha = ("ad" + UUID.randomUUID().toString().replace("-", "") + "0".repeat(30)).substring(0, 64);
        jdbcTemplate.update(
                "INSERT INTO file_verdicts (sha256, verdict, verdict_source) VALUES (?, 'CLEAN', 'ENGINE')", sha);
        return sha;
    }

    private boolean stale(String sha) {
        return jdbcTemplate.queryForObject("SELECT is_stale FROM file_verdicts WHERE sha256 = ?", Boolean.class, sha);
    }

    private ResponseEntity<Map> patch(int version, UUID ruleId, Map<String, Object> body) {
        return adminClient.exchange("/api/admin/rulesets/" + version + "/rules/" + ruleId, HttpMethod.PATCH, body,
                Map.class);
    }

    private int auditCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action IN "
                + "('ENABLE_YARA_RULE', 'DISABLE_YARA_RULE')", Integer.class);
    }

    @Test
    void 활성_버전의_룰을_끄면_stale이_되고_엔진_키가_바뀐다() {
        String sha = seedEngineVerdict();
        UUID ruleId = ruleId(1);
        activeRulesetService.pushActive();
        String before = FakeDetectionEngineService.loadedKey();

        ResponseEntity<Map> response = patch(1, ruleId, Map.of("enabled", false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("enabled")).isEqualTo(false);
        assertThat(stale(sha)).isTrue();
        // 방금 커밋된 토글이 푸시된 스냅샷에 반영돼야 한다(꺼진 룰 1개)
        assertThat(FakeDetectionEngineService.loadedKey()).isNotEqualTo(before)
                .isEqualTo(activeRulesetService.activeSnapshot().key());
        assertThat(activeRulesetService.activeSnapshot().disabledRuleNames()).hasSize(1);
        Map<String, Object> audit = jdbcTemplate.queryForMap(
                "SELECT target_type, target_id, detail::text AS detail FROM audit_logs "
                        + "WHERE action = 'DISABLE_YARA_RULE'");
        assertThat(audit.get("target_type")).isEqualTo("YARA_RULE");
        assertThat(audit.get("target_id")).isEqualTo(ruleId.toString());
    }

    @Test
    void 같은_값으로_바꾸면_아무것도_하지_않는다() {
        String sha = seedEngineVerdict();

        ResponseEntity<Map> response = patch(1, ruleId(1), Map.of("enabled", true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stale(sha)).isFalse();
        assertThat(auditCount()).isZero();
        assertThat(FakeDetectionEngineService.loadCount()).isZero();
    }

    @Test
    void 대기_버전의_룰을_끄면_stale도_적재도_없다() {
        String sha = seedEngineVerdict();
        int version = uploadPending();

        ResponseEntity<Map> response = patch(version, ruleId(version), Map.of("enabled", false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("enabled")).isEqualTo(false);
        assertThat(stale(sha)).isFalse();
        assertThat(FakeDetectionEngineService.loadCount()).isZero();
        assertThat(auditCount()).isEqualTo(1);
    }

    @Test
    void enabled가_없으면_400() {
        ResponseEntity<Map> response = patch(1, ruleId(1), Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 다른_버전의_ruleId면_404() {
        int version = uploadPending();

        assertThat(patch(1, ruleId(version), Map.of("enabled", false)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(patch(9999, ruleId(1), Map.of("enabled", false)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
