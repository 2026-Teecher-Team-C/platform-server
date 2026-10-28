package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

// 룰셋 업로드. 엔진 컴파일은 FakeDetectionEngineService가 대신한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@AutoConfigureTestGrpcTransport
@Import({TestcontainersConfiguration.class, AdminApiClient.class})
class RulesetUploadRestIntegrationTest {

    private static final String URL = "/api/admin/rulesets";
    private static final String TWO_RULES = """
            rule Alpha_Rule { meta: severity = "HIGH" condition: true }
            rule Beta_Rule { condition: false }
            """;

    @Autowired
    private AdminApiClient adminClient;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void 시드_외_버전을_지운다() {
        FakeDetectionEngineService.reset();
        jdbcTemplate.update("DELETE FROM audit_logs WHERE action = 'UPLOAD_RULESET'");
        jdbcTemplate.update("DELETE FROM yara_rulesets WHERE rulesets_version > 1");
    }

    private int maxVersion() {
        return jdbcTemplate.queryForObject("SELECT MAX(rulesets_version) FROM yara_rulesets", Integer.class);
    }

    private int rulesetCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM yara_rulesets", Integer.class);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map<String, Object>> upload(String filename, byte[] content) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminClient.accessToken());
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return restTemplate.exchange(URL, HttpMethod.POST, new HttpEntity<>(body, headers),
                (Class<Map<String, Object>>) (Class<?>) Map.class);
    }

    private ResponseEntity<Map<String, Object>> upload(String filename, String content) {
        return upload(filename, content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void 올린_파일은_대기_버전이_되고_룰은_모두_켜져_있다() {
        int before = maxVersion();

        ResponseEntity<Map<String, Object>> response = upload("my.yar", TWO_RULES);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("version")).isEqualTo(before + 1);
        assertThat(body.get("isActive")).isEqualTo(false);
        assertThat(body.get("ruleCount")).isEqualTo(2);
        assertThat(body.get("disabledRuleCount")).isEqualTo(0);

        List<Map<String, Object>> rules = jdbcTemplate.queryForList(
                "SELECT rule_name, severity, enabled FROM yara_rules WHERE rulesets_version = ? ORDER BY rule_name",
                before + 1);
        assertThat(rules).hasSize(2);
        assertThat(rules).allMatch(r -> Boolean.TRUE.equals(r.get("enabled")));
        assertThat(rules.get(0).get("severity")).isEqualTo("HIGH");
        assertThat(rules.get(1).get("severity")).isEqualTo("MEDIUM");

        List<Map<String, Object>> audits = jdbcTemplate.queryForList(
                "SELECT target_type, target_id, detail::text AS detail FROM audit_logs "
                        + "WHERE action = 'UPLOAD_RULESET'");
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).get("target_type")).isEqualTo("RULESET");
        assertThat(audits.get(0).get("target_id")).isEqualTo(String.valueOf(before + 1));
        assertThat((String) audits.get(0).get("detail")).contains("my.yar").contains("2");
    }

    @Test
    void 문법_오류는_422이고_엔진_메시지를_준다() {
        int before = rulesetCount();

        ResponseEntity<Map<String, Object>> response = upload("bad.yar", "rule X { SYNTAX_ERROR }");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((String) response.getBody().get("message")).contains("syntax error");
        assertThat(rulesetCount()).isEqualTo(before);
    }

    @Test
    void 룰이_없는_파일은_422() {
        int before = rulesetCount();

        ResponseEntity<Map<String, Object>> response = upload("empty-rules.yar", "// only a comment\n");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((String) response.getBody().get("message")).contains("룰이 하나도 없습니다.");
        assertThat(rulesetCount()).isEqualTo(before);
    }

    @Test
    void yar가_아닌_파일과_빈_파일은_400() {
        int before = rulesetCount();

        assertThat(upload("rules.txt", TWO_RULES).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(upload("empty.yar", new byte[0]).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rulesetCount()).isEqualTo(before);
    }

    @Test
    void 일_MiB를_넘으면_400() {
        int before = rulesetCount();
        byte[] big = new byte[1_048_577];
        java.util.Arrays.fill(big, (byte) ' ');

        ResponseEntity<Map<String, Object>> response = upload("big.yar", big);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rulesetCount()).isEqualTo(before);
    }

    @Test
    void 엔진에_닿지_않으면_503() {
        int before = rulesetCount();
        FakeDetectionEngineService.failNextCall();

        ResponseEntity<Map<String, Object>> response = upload("my.yar", TWO_RULES);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(rulesetCount()).isEqualTo(before);
    }
}
