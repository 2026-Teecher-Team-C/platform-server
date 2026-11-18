package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

// IMP-8: /api/admin/similarity-policy 조회·변경 API.
// 임계값은 DB 한 행짜리 전역 설정이다. 테스트가 값을 바꾼 채 끝나면 같은 컨테이너를 쓰는
// 다른 테스트가 30/70을 가정하고 돌다가 깨지므로 매번 끝날 때 되돌린다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfiguration.class, AdminApiClient.class})
class SimilarityThresholdRestIntegrationTest {

    private static final String URL = "/api/admin/similarity-policy";
    private static final String ANALYST_PASSWORD = "analyst-test-password";

    @Autowired
    private AdminApiClient adminClient;
    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @AfterEach
    void 임계값을_기본값으로_되돌린다() {
        jdbcTemplate.update("UPDATE similarity_corpus_state SET block_distance = 30, warn_distance = 70");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 조회하면_현재_값과_허용_범위를_준다() {
        ResponseEntity<Map> response = adminClient.getForEntity(URL, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("blockDistance", 30).containsEntry("warnDistance", 70);
        Map<String, Object> limits = (Map<String, Object>) response.getBody().get("limits");
        assertThat(limits).containsEntry("blockMin", 0).containsEntry("blockMax", 50)
                .containsEntry("warnMax", 100);
    }

    @Test
    void 변경하면_응답과_다음_조회에_새_값이_나온다() {
        ResponseEntity<Map> put = put(adminClient, Map.of("blockDistance", 40, "warnDistance", 80));

        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(put.getBody()).containsEntry("blockDistance", 40).containsEntry("warnDistance", 80);

        ResponseEntity<Map> get = adminClient.getForEntity(URL, Map.class);
        assertThat(get.getBody()).containsEntry("blockDistance", 40).containsEntry("warnDistance", 80);
    }

    @Test
    void 변경하면_판정_토큰이_바뀌어_캐시된_판정이_무효가_된다() {
        assertThat(corpusToken()).endsWith(":tlsh-30-70-v1");

        put(adminClient, Map.of("blockDistance", 42, "warnDistance", 82));

        assertThat(corpusToken()).endsWith(":tlsh-42-82-v1");
    }

    @Test
    void 변경하면_감사_로그에_이전_값과_새_값이_남는다() {
        put(adminClient, Map.of("blockDistance", 41, "warnDistance", 81));

        // 값(41)이 이 테스트에서만 쓰이므로 detail로 행을 하나로 좁힐 수 있다.
        Map<String, Object> row = jdbcTemplate.queryForMap(
                """
                SELECT target_type, target_id,
                       detail::jsonb ->> 'previousBlockDistance' AS prev_block,
                       detail::jsonb ->> 'previousWarnDistance' AS prev_warn,
                       detail::jsonb ->> 'warnDistance' AS warn
                FROM audit_logs
                WHERE action = 'UPDATE_SIMILARITY_THRESHOLD' AND detail::jsonb ->> 'blockDistance' = '41'
                """);

        assertThat(row.get("target_type")).isEqualTo("SIMILARITY_POLICY");
        assertThat(row.get("target_id")).isEqualTo("TLSH");
        assertThat(row.get("prev_block")).isEqualTo("30");
        assertThat(row.get("prev_warn")).isEqualTo("70");
        assertThat(row.get("warn")).isEqualTo("81");
    }

    @Test
    void 값이_그대로면_감사_로그를_남기지_않는다() {
        int before = auditCount();

        ResponseEntity<Map> response = put(adminClient, Map.of("blockDistance", 30, "warnDistance", 70));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(auditCount()).isEqualTo(before);
    }

    @Test
    void 범위를_벗어난_값은_400이고_저장되지_않는다() {
        // 차단 51(상한 초과), 경고 <= 차단, 경고 101(상한 초과)
        assertThat(put(adminClient, Map.of("blockDistance", 51, "warnDistance", 80)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put(adminClient, Map.of("blockDistance", 30, "warnDistance", 30)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put(adminClient, Map.of("blockDistance", 30, "warnDistance", 101)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(adminClient.getForEntity(URL, Map.class).getBody())
                .containsEntry("blockDistance", 30).containsEntry("warnDistance", 70);
    }

    @Test
    void 필드가_빠지거나_숫자가_아니면_400이다() {
        // 빠진 필드가 0으로 읽혀 차단 0으로 저장되면 안 된다.
        assertThat(put(adminClient, Map.of("blockDistance", 30)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put(adminClient, Map.of("blockDistance", "abc", "warnDistance", 70)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void ANALYST는_조회만_되고_변경은_403이다() {
        String token = analystToken();

        ResponseEntity<Map> get = restTemplate.exchange(URL, HttpMethod.GET, bearer(token, null), Map.class);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> put = restTemplate.exchange(URL, HttpMethod.PUT,
                bearer(token, Map.of("blockDistance", 40, "warnDistance", 80)), Map.class);
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(adminClient.getForEntity(URL, Map.class).getBody()).containsEntry("blockDistance", 30);
    }

    @Test
    void 로그인하지_않으면_401이다() {
        assertThat(restTemplate.getForEntity(URL, Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.exchange(URL, HttpMethod.PUT,
                new HttpEntity<>(Map.of("blockDistance", 40, "warnDistance", 80), jsonHeaders()), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---- 도우미 ----

    private ResponseEntity<Map> put(AdminApiClient client, Map<String, Object> body) {
        return client.exchange(URL, HttpMethod.PUT, body, Map.class);
    }

    private int auditCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = 'UPDATE_SIMILARITY_THRESHOLD'", Integer.class);
    }

    // 뷰의 컬럼 이름에 기대지 않으려고 첫 컬럼 값을 그대로 꺼낸다(뷰는 토큰 한 값을 돌려준다).
    private String corpusToken() {
        Map<String, Object> row =
                jdbcTemplate.queryForList("SELECT * FROM current_similarity_corpus_token").get(0);
        return String.valueOf(row.values().iterator().next());
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpEntity<Object> bearer(String token, Object body) {
        HttpHeaders headers = jsonHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(body, headers);
    }

    // AdminApiClient는 ADMIN만 만들 수 있어서, ANALYST 계정을 직접 넣고 로그인한다.
    @SuppressWarnings("unchecked")
    private String analystToken() {
        String name = "test-analyst-" + UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO admin_users (admin_id, username, password_hash, role, is_active)"
                        + " VALUES (?, ?, ?, 'ANALYST', true)",
                UUID.randomUUID(), name, passwordEncoder.encode(ANALYST_PASSWORD));
        ResponseEntity<Map> login = restTemplate.exchange("/api/admin/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("username", name, "password", ANALYST_PASSWORD), jsonHeaders()),
                Map.class);
        return (String) login.getBody().get("accessToken");
    }
}