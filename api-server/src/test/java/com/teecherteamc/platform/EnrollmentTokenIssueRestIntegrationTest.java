package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.teecherteamc.platform.fleet.application.RegisterAgentCommand;
import com.teecherteamc.platform.fleet.application.RegisterAgentUseCase;
import com.teecherteamc.platform.fleet.domain.EnrollmentTokenAlreadyRedeemedException;
import com.teecherteamc.platform.fleet.domain.EnrollmentTokenExpiredException;
import com.teecherteamc.platform.fleet.domain.TokenHash;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * POST /api/admin/agents/enrollment-tokens. 로그인이 필요한 경로라서 AdminApiClient로 로그인해서 요청한다.
 * 발급한 토큰이 실제로 쓸 수 있는지는 RegisterAgentUseCase를 직접 불러서 확인한다
 * (gRPC 쪽 등록 테스트는 RegisterAgentGrpcIntegrationTest가 따로 있다).
 */
@SuppressWarnings("unchecked")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfiguration.class, AdminApiClient.class})
class EnrollmentTokenIssueRestIntegrationTest {

    private static final String URL = "/api/admin/agents/enrollment-tokens";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AdminApiClient adminApi;

    @Autowired
    private RegisterAgentUseCase registerAgentUseCase;

    private static Class<Map<String, Object>> mapType() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }

    private ResponseEntity<Map<String, Object>> issue(Map<String, Object> body) {
        return adminApi.postForEntity(URL, body, mapType());
    }

    /** 발급 요청을 보내고 응답의 평문 토큰만 꺼낸다. */
    private String issueToken(Map<String, Object> body) {
        return (String) issue(body).getBody().get("token");
    }

    private Integer rowCountByHash(String value) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM agent_enrollment_tokens WHERE enrollment_token_hash = ?", Integer.class, value);
    }

    /** 테스트마다 다른 hostname을 써서 다른 에이전트 테스트와 섞이지 않게 한다. */
    private void register(String token) {
        registerAgentUseCase.register(new RegisterAgentCommand(
                token, "enroll-test-" + UUID.randomUUID(), "WINDOWS", "1.0.0", null));
    }

    @Test
    void 발급하면_201이고_enr_접두어_토큰과_기본_60분_뒤_만료시각이_온다() {
        Instant before = Instant.now();

        ResponseEntity<Map<String, Object>> response = issue(Map.of());   // 본문이 {} 이면 기본값

        Instant after = Instant.now();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat((String) response.getBody().get("token")).startsWith("enr_");
        Instant expiresAt = Instant.parse((String) response.getBody().get("expiresAt"));
        assertThat(expiresAt).isBetween(before.plusSeconds(60 * 60), after.plusSeconds(60 * 60));
    }

    @Test
    void 지정한_분이_만료시각에_반영된다() {
        String token = issueToken(Map.of("expiresInMinutes", 30));

        // 만료 시각 - 발급 시각 = 30분 (초 단위로 계산해서 시간대 영향을 받지 않는다)
        Double seconds = jdbcTemplate.queryForObject(
                "SELECT EXTRACT(EPOCH FROM (expires_at - created_at)) FROM agent_enrollment_tokens"
                        + " WHERE enrollment_token_hash = ?",
                Double.class, TokenHash.sha256Hex(token));
        assertThat(seconds).isEqualTo(30 * 60.0);
    }

    @Test
    void DB에는_해시만_저장되고_평문은_없다() {
        String token = issueToken(Map.of());

        assertThat(rowCountByHash(TokenHash.sha256Hex(token))).isEqualTo(1);   // 해시로는 찾아진다
        assertThat(rowCountByHash(token)).isZero();                            // 평문 그대로는 없다
    }

    @Test
    void 발급할_때마다_다른_토큰이_나온다() {
        assertThat(issueToken(Map.of())).isNotEqualTo(issueToken(Map.of()));
    }

    @Test
    void 범위_밖_expiresInMinutes는_400이다() {
        for (int invalid : new int[] {0, -5, 1441}) {
            ResponseEntity<Map<String, Object>> response = issue(Map.of("expiresInMinutes", invalid));

            assertThat(response.getStatusCode()).as("값 %d", invalid).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().get("code")).isEqualTo("INVALID_INPUT");
        }
    }

    @Test
    void 숫자가_아닌_expiresInMinutes는_400이다() {
        ResponseEntity<Map<String, Object>> response = issue(Map.of("expiresInMinutes", "abc"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 감사_로그에_발급이_남고_평문_토큰은_들어가지_않는다() {
        String token = issueToken(Map.of("expiresInMinutes", 45));

        String tokenId = jdbcTemplate.queryForObject(
                "SELECT enrollment_token_id::text FROM agent_enrollment_tokens WHERE enrollment_token_hash = ?",
                String.class, TokenHash.sha256Hex(token));
        Map<String, Object> audit = jdbcTemplate.queryForMap(
                "SELECT action, target_type, target_id, actor_id, detail::text AS detail"
                        + " FROM audit_logs WHERE target_id = ?",
                tokenId);
        assertThat(audit.get("action")).isEqualTo("ISSUE_ENROLLMENT_TOKEN");
        assertThat(audit.get("target_type")).isEqualTo("ENROLLMENT_TOKEN");
        assertThat(audit.get("actor_id").toString()).isEqualTo(adminApi.adminId().toString());
        assertThat((String) audit.get("detail")).contains("expiresAt").doesNotContain(token);

        // issued_by에도 발급한 관리자가 들어간다
        String issuedBy = jdbcTemplate.queryForObject(
                "SELECT issued_by::text FROM agent_enrollment_tokens WHERE enrollment_token_id::text = ?",
                String.class, tokenId);
        assertThat(issuedBy).isEqualTo(adminApi.adminId().toString());
    }

    @Test
    void 발급한_토큰으로_등록이_되고_같은_토큰을_다시_쓰면_실패한다() {
        String token = issueToken(Map.of());

        register(token);   // 예외 없이 끝나면 성공

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM agents WHERE enrollment_token_hash = ?",
                        Integer.class, TokenHash.sha256Hex(token)))
                .isEqualTo(1);
        assertThatThrownBy(() -> register(token)).isInstanceOf(EnrollmentTokenAlreadyRedeemedException.class);
    }

    @Test
    void 만료된_토큰으로_등록하면_실패한다() {
        String token = issueToken(Map.of("expiresInMinutes", 1));
        // 시계를 돌리는 대신 DB에서 만료 시각을 과거로 바꾼다 (발급 시각도 같이 앞당겨 시각 순서를 맞춘다)
        jdbcTemplate.update(
                "UPDATE agent_enrollment_tokens SET created_at = ?, expires_at = ? WHERE enrollment_token_hash = ?",
                Timestamp.from(Instant.now().minusSeconds(2 * 60 * 60)),
                Timestamp.from(Instant.now().minusSeconds(60)),
                TokenHash.sha256Hex(token));

        assertThatThrownBy(() -> register(token)).isInstanceOf(EnrollmentTokenExpiredException.class);
    }

    @Test
    void 로그인하지_않으면_401이다() {
        ResponseEntity<Map<String, Object>> response =
                restTemplate.exchange(URL, HttpMethod.POST, HttpEntity.EMPTY, mapType());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code")).isEqualTo("UNAUTHORIZED");
    }
}