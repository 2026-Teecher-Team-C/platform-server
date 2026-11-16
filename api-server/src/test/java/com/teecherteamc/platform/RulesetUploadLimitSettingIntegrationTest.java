package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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

// 상한 설정값만 바꿔도 multipart·Tomcat 한도와 안내 문구가 함께 따라가는지 본다. (기본 32MiB → 40MiB)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ruleset.max-source-bytes=41943040",
        "ruleset.max-request-bytes=42991616"})
@AutoConfigureTestRestTemplate
@AutoConfigureTestGrpcTransport
@Import({TestcontainersConfiguration.class, AdminApiClient.class})
class RulesetUploadLimitSettingIntegrationTest {

    private static final String URL = "/api/admin/rulesets";
    private static final int THIRTY_THREE_MIB = 33 * 1024 * 1024;

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

    @Test
    void 상한을_40MiB로_올리면_33MiB_룰셋도_올라간다() {
        ResponseEntity<Map<String, Object>> response = upload(ruleOfSize(THIRTY_THREE_MIB));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void 상한을_40MiB로_올리면_넘을_때_문구도_40MiB다() {
        ResponseEntity<Map<String, Object>> response = upload(ruleOfSize(41_943_041));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("message")).isEqualTo("파일은 40MiB 이하여야 합니다.");
    }

    private static byte[] ruleOfSize(int size) {
        byte[] rule = "rule Big_Rule { condition: true }\n".getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[size];
        Arrays.fill(content, (byte) ' ');
        System.arraycopy(rule, 0, content, 0, rule.length);
        return content;
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map<String, Object>> upload(byte[] content) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return "big.yar";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminClient.accessToken());
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return restTemplate.exchange(URL, HttpMethod.POST, new HttpEntity<>(body, headers),
                (Class<Map<String, Object>>) (Class<?>) Map.class);
    }
}
