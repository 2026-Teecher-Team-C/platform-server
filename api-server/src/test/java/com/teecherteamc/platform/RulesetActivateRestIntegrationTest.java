package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.teecherteamc.platform.ruleset.application.ActiveRulesetService;
import com.teecherteamc.platform.ruleset.application.UploadRulesetUseCase;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

// 룰셋 활성화. 엔진 적재는 FakeDetectionEngineService가 대신한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@AutoConfigureTestGrpcTransport
@Import({TestcontainersConfiguration.class, AdminApiClient.class})
class RulesetActivateRestIntegrationTest {

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
        jdbcTemplate.update("DELETE FROM file_verdicts WHERE sha256 LIKE 'ac%'");
        RulesetTestSupport.restoreSeed(jdbcTemplate);
    }

    private int uploadPending() {
        return uploadRulesetUseCase.upload(new UploadRulesetUseCase.Command(
                "v.yar", SOURCE.getBytes(StandardCharsets.UTF_8), adminClient.adminId(), "127.0.0.1")).getVersion();
    }

    private String seedVerdict(String prefix, String source) {
        String sha = prefix + UUID.randomUUID().toString().replace("-", "") + "0".repeat(30);
        sha = sha.substring(0, 64);
        jdbcTemplate.update("INSERT INTO file_verdicts (sha256, verdict, verdict_source) VALUES (?, 'CLEAN', ?)",
                sha, source);
        return sha;
    }

    private boolean stale(String sha) {
        return jdbcTemplate.queryForObject("SELECT is_stale FROM file_verdicts WHERE sha256 = ?", Boolean.class, sha);
    }

    private List<Integer> activeVersions() {
        return jdbcTemplate.queryForList("SELECT rulesets_version FROM yara_rulesets WHERE is_active", Integer.class);
    }

    private int auditCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = 'ACTIVATE_RULESET'", Integer.class);
    }

    private ResponseEntity<Map> activate(int version) {
        return adminClient.postForEntity("/api/admin/rulesets/" + version + "/activate", null, Map.class);
    }

    @Test
    void 활성화하면_이전_버전은_내려가고_ENGINE_판정만_stale이_된다() {
        String engineSha = seedVerdict("ac", "ENGINE");
        String manualSha = seedVerdict("ac", "MANUAL");
        int version = uploadPending();

        ResponseEntity<Map> response = activate(version);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("version")).isEqualTo(version);
        assertThat(response.getBody().get("isActive")).isEqualTo(true);
        assertThat(activeVersions()).containsExactly(version);
        assertThat(stale(engineSha)).isTrue();
        assertThat(stale(manualSha)).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT (detail::jsonb)->>'previousVersion' FROM audit_logs "
                + "WHERE action = 'ACTIVATE_RULESET'", String.class)).isEqualTo("1");
        assertThat(auditCount()).isEqualTo(1);
        assertThat(FakeDetectionEngineService.loadedKey()).isEqualTo(activeRulesetService.activeSnapshot().key());
        assertThat(FakeDetectionEngineService.loadedVersion()).isEqualTo(version);
    }

    @Test
    void 이미_활성인_버전을_다시_활성화하면_200이고_아무것도_바뀌지_않는다() {
        String engineSha = seedVerdict("ac", "ENGINE");

        ResponseEntity<Map> response = activate(1);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stale(engineSha)).isFalse();
        assertThat(auditCount()).isZero();
        assertThat(FakeDetectionEngineService.loadCount()).isZero();
        assertThat(activeVersions()).containsExactly(1);
    }

    @Test
    void 이전_버전을_다시_활성화해_되돌릴_수_있다() {
        int version = uploadPending();
        activate(version);

        ResponseEntity<Map> response = activate(1);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(activeVersions()).containsExactly(1);
        assertThat(FakeDetectionEngineService.loadedVersion()).isEqualTo(1);
    }

    @Test
    void 없는_버전과_v0_활성화는_404() {
        assertThat(activate(9999).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(activate(0).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(activeVersions()).containsExactly(1);
    }

    @Test
    void 엔진_적재가_실패해도_활성화는_200() {
        String engineSha = seedVerdict("ac", "ENGINE");
        int version = uploadPending();
        FakeDetectionEngineService.failNextCall();

        ResponseEntity<Map> response = activate(version);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stale(engineSha)).isTrue();
        assertThat(auditCount()).isEqualTo(1);
        assertThat(activeVersions()).containsExactly(version);
        assertThat(FakeDetectionEngineService.loadCount()).isZero();
    }
}
