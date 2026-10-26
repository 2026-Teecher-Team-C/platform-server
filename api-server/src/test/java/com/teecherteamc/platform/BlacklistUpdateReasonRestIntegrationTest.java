package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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
 * PATCH /api/admin/blacklist/{sha256}. 로그인이 필요한 경로라서 AdminApiClient로 로그인해서 요청한다.
 * 같은 DB를 다른 테스트도 쓰므로 테스트마다 랜덤 64자 해시를 쓰고, hash_blacklist에 직접 넣어서 시작한다.
 */
@SuppressWarnings("unchecked")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfiguration.class, AdminApiClient.class})
class BlacklistUpdateReasonRestIntegrationTest {

    private static final String URL = "/api/admin/blacklist";
    private static final Instant CREATED_AT = Instant.parse("2026-10-20T01:00:00Z");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AdminApiClient adminApi;

    // 64자 소문자 hex
    private static String newHash() {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
    }

    private static Class<Map<String, Object>> mapType() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }

    private ResponseEntity<Map<String, Object>> patch(String hash, Object body) {
        return adminApi.patchForEntity(URL + "/" + hash, body, mapType());
    }

    private void seed(String hash, UUID addedBy, boolean active) {
        jdbcTemplate.update(
                """
                INSERT INTO hash_blacklist
                    (hash_type, hash_value, reason, severity, source, added_by, is_active, created_at, deactivated_at)
                VALUES ('SHA256', ?, '처음 사유', 'HIGH', 'MANUAL', ?, ?, ?, ?)
                """,
                hash, addedBy, active, Timestamp.from(CREATED_AT),
                active ? null : Timestamp.from(CREATED_AT));
    }

    private String reasonOf(String hash) {
        return jdbcTemplate.queryForObject(
                "SELECT reason FROM hash_blacklist WHERE hash_value = ?", String.class, hash);
    }

    private Integer auditCountOf(String hash) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE target_id = ?", Integer.class, "SHA256:" + hash);
    }

    @Test
    void 사유만_바뀌고_다른_필드는_그대로다() {
        String hash = newHash();
        seed(hash, adminApi.adminId(), true);

        ResponseEntity<Map<String, Object>> response = patch(hash, Map.of("reason", "EICAR 테스트 파일 (시연용)"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("reason")).isEqualTo("EICAR 테스트 파일 (시연용)");
        assertThat(body.get("hashType")).isEqualTo("SHA256");
        assertThat(body.get("hashValue")).isEqualTo(hash);
        assertThat(body.get("severity")).isEqualTo("HIGH");
        assertThat(body.get("source")).isEqualTo("MANUAL");
        assertThat(body.get("isActive")).isEqualTo(true);
        assertThat(Instant.parse((String) body.get("createdAt"))).isEqualTo(CREATED_AT);
        Map<String, Object> addedBy = (Map<String, Object>) body.get("addedBy");
        assertThat(addedBy.get("adminId")).isEqualTo(adminApi.adminId().toString());
        assertThat(addedBy.get("username")).isEqualTo(adminApi.username());

        // DB에도 사유만 바뀌고 나머지는 그대로다
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT reason, severity, source, is_active FROM hash_blacklist WHERE hash_value = ?", hash);
        assertThat(row.get("reason")).isEqualTo("EICAR 테스트 파일 (시연용)");
        assertThat(row.get("severity")).isEqualTo("HIGH");
        assertThat(row.get("source")).isEqualTo("MANUAL");
        assertThat(row.get("is_active")).isEqualTo(true);
    }

    @Test
    void 등록자가_없는_항목도_수정되고_addedBy는_null이다() {
        String hash = newHash();
        seed(hash, null, true);

        ResponseEntity<Map<String, Object>> response = patch(hash, Map.of("reason", "새 사유"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("reason")).isEqualTo("새 사유");
        assertThat(response.getBody().get("addedBy")).isNull();
    }

    @Test
    void 해시를_대문자로_보내도_찾아서_수정한다() {
        String hash = newHash();
        seed(hash, adminApi.adminId(), true);

        ResponseEntity<Map<String, Object>> response = patch(hash.toUpperCase(), Map.of("reason", "대문자 요청"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reasonOf(hash)).isEqualTo("대문자 요청");
    }

    @Test
    void 없는_해시는_404이고_감사_로그를_남기지_않는다() {
        String hash = newHash();

        ResponseEntity<Map<String, Object>> response = patch(hash, Map.of("reason", "없는 해시"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("NOT_FOUND");
        assertThat(auditCountOf(hash)).isZero();
    }

    @Test
    void 해제된_항목은_404이고_사유는_그대로다() {
        String hash = newHash();
        seed(hash, adminApi.adminId(), false);

        ResponseEntity<Map<String, Object>> response = patch(hash, Map.of("reason", "해제된 항목"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("NOT_FOUND");
        assertThat(reasonOf(hash)).isEqualTo("처음 사유");
        assertThat(auditCountOf(hash)).isZero();
    }

    @Test
    void 빈_사유는_400이고_fieldErrors에_reason이_담긴다() {
        String hash = newHash();
        seed(hash, adminApi.adminId(), true);

        // 빈 문자열, 공백, 사유 없음 모두 막는다
        List<Object> badBodies = List.of(Map.of("reason", ""), Map.of("reason", "   "), Map.of());

        for (Object badBody : badBodies) {
            ResponseEntity<Map<String, Object>> response = patch(hash, badBody);

            assertThat(response.getStatusCode()).as(badBody.toString()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().get("code")).as(badBody.toString()).isEqualTo("INVALID_INPUT");
            List<Map<String, Object>> fieldErrors = (List<Map<String, Object>>) response.getBody().get("fieldErrors");
            assertThat(fieldErrors).anyMatch(e -> "reason".equals(e.get("field")));
        }
        assertThat(reasonOf(hash)).isEqualTo("처음 사유");
    }

    @Test
    void 해시_형식이_틀리면_400이다() {
        ResponseEntity<Map<String, Object>> response = patch("not-a-hash", Map.of("reason", "형식 오류"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_INPUT");
    }

    @Test
    void 감사_로그에_이전_사유와_이후_사유를_남긴다() {
        String hash = newHash();
        seed(hash, adminApi.adminId(), true);

        patch(hash, Map.of("reason", "바뀐 사유"));

        Map<String, Object> audit = jdbcTemplate.queryForMap(
                """
                SELECT action, target_type, target_id, actor_id,
                    detail::jsonb ->> 'previousReason' AS previous_reason,
                    detail::jsonb ->> 'newReason' AS new_reason
                FROM audit_logs WHERE target_id = ?
                """,
                "SHA256:" + hash);
        assertThat(audit.get("action")).isEqualTo("UPDATE_BLACKLIST");
        assertThat(audit.get("target_type")).isEqualTo("BLACKLIST");
        assertThat(audit.get("target_id")).isEqualTo("SHA256:" + hash);
        assertThat(audit.get("actor_id").toString()).isEqualTo(adminApi.adminId().toString());
        assertThat(audit.get("previous_reason")).isEqualTo("처음 사유");
        assertThat(audit.get("new_reason")).isEqualTo("바뀐 사유");
    }

    @Test
    void 로그인하지_않으면_401이다() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                URL + "/" + newHash(), HttpMethod.PATCH,
                new HttpEntity<>(Map.of("reason", "로그인 없음")), mapType());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code")).isEqualTo("UNAUTHORIZED");
    }
}