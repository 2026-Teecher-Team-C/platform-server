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
 * DELETE /api/admin/blacklist/{sha256}. 로그인이 필요한 경로라서 AdminApiClient로 로그인해서 요청한다.
 * 같은 DB를 다른 테스트도 쓰므로 테스트마다 랜덤 64자 해시를 쓰고, hash_blacklist에 직접 넣어서 시작한다.
 */
@SuppressWarnings("unchecked")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfiguration.class, AdminApiClient.class})
class BlacklistRemoveRestIntegrationTest {

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

    private void seed(String hash, boolean active) {
        jdbcTemplate.update(
                """
                INSERT INTO hash_blacklist
                    (hash_type, hash_value, reason, severity, source, added_by, is_active, created_at, deactivated_at)
                VALUES ('SHA256', ?, '처음 사유', 'HIGH', 'MANUAL', ?, ?, ?, ?)
                """,
                hash, adminApi.adminId(), active, Timestamp.from(CREATED_AT),
                active ? null : Timestamp.from(CREATED_AT));
    }

    private Map<String, Object> rowOf(String hash) {
        return jdbcTemplate.queryForMap(
                "SELECT reason, severity, source, is_active, deactivated_at FROM hash_blacklist WHERE hash_value = ?",
                hash);
    }

    private Integer auditCountOf(String hash) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE target_id = ?", Integer.class, "SHA256:" + hash);
    }

    private List<Map<String, Object>> listOf(String hash, String query) {
        ResponseEntity<Map<String, Object>> response =
                adminApi.getForEntity(URL + "?hashValue=" + hash + query, mapType());
        return (List<Map<String, Object>>) response.getBody().get("content");
    }

    @Test
    void 해제하면_204이고_행은_남고_비활성이_된다() {
        String hash = newHash();
        seed(hash, true);

        ResponseEntity<Void> response = adminApi.deleteForEntity(URL + "/" + hash, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        Map<String, Object> row = rowOf(hash);   // 행이 지워지지 않고 남아 있다
        assertThat(row.get("is_active")).isEqualTo(false);
        assertThat(row.get("deactivated_at")).isNotNull();
        // 사유, 심각도, 출처는 그대로
        assertThat(row.get("reason")).isEqualTo("처음 사유");
        assertThat(row.get("severity")).isEqualTo("HIGH");
        assertThat(row.get("source")).isEqualTo("MANUAL");
    }

    @Test
    void 해제하면_기본_목록에서_빠지고_isActive_false_목록에서_보인다() {
        String hash = newHash();
        seed(hash, true);
        assertThat(listOf(hash, "")).hasSize(1);   // 해제 전에는 기본 목록에 있다

        adminApi.deleteForEntity(URL + "/" + hash, Void.class);

        assertThat(listOf(hash, "")).isEmpty();
        List<Map<String, Object>> released = listOf(hash, "&isActive=false");
        assertThat(released).hasSize(1);
        assertThat(released.get(0).get("isActive")).isEqualTo(false);
        assertThat(released.get(0).get("deactivatedAt")).isNotNull();
    }

    @Test
    void 해제한_해시를_다시_등록하면_201이고_되살아난다() {
        String hash = newHash();
        seed(hash, true);
        adminApi.deleteForEntity(URL + "/" + hash, Void.class);

        ResponseEntity<Map<String, Object>> response = adminApi.postForEntity(
                URL, Map.of("sha256", hash, "reason", "다시 등록"), mapType());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> row = rowOf(hash);
        assertThat(row.get("is_active")).isEqualTo(true);
        assertThat(row.get("deactivated_at")).isNull();
        assertThat(row.get("reason")).isEqualTo("다시 등록");
    }

    @Test
    void 해시를_대문자로_보내도_해제된다() {
        String hash = newHash();
        seed(hash, true);

        ResponseEntity<Void> response = adminApi.deleteForEntity(URL + "/" + hash.toUpperCase(), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rowOf(hash).get("is_active")).isEqualTo(false);
    }

    @Test
    void 없는_해시는_404이고_감사_로그를_남기지_않는다() {
        String hash = newHash();

        ResponseEntity<Map<String, Object>> response = adminApi.deleteForEntity(URL + "/" + hash, mapType());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("NOT_FOUND");
        assertThat(auditCountOf(hash)).isZero();
    }

    @Test
    void 이미_해제된_항목은_404이고_해제_시각은_그대로다() {
        String hash = newHash();
        seed(hash, false);

        ResponseEntity<Map<String, Object>> response = adminApi.deleteForEntity(URL + "/" + hash, mapType());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("NOT_FOUND");
        Integer unchanged = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hash_blacklist WHERE hash_value = ? AND deactivated_at = ?",
                Integer.class, hash, Timestamp.from(CREATED_AT));
        assertThat(unchanged).isEqualTo(1);   // seed에서 넣은 해제 시각이 바뀌지 않았다
        assertThat(auditCountOf(hash)).isZero();
    }

    @Test
    void 해시_형식이_틀리면_400이다() {
        ResponseEntity<Map<String, Object>> response = adminApi.deleteForEntity(URL + "/not-a-hash", mapType());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_INPUT");
    }

    @Test
    void 감사_로그에_REMOVE_BLACKLIST를_남긴다() {
        String hash = newHash();
        seed(hash, true);

        adminApi.deleteForEntity(URL + "/" + hash, Void.class);

        Map<String, Object> audit = jdbcTemplate.queryForMap(
                "SELECT action, target_type, target_id, actor_id FROM audit_logs WHERE target_id = ?",
                "SHA256:" + hash);
        assertThat(audit.get("action")).isEqualTo("REMOVE_BLACKLIST");
        assertThat(audit.get("target_type")).isEqualTo("BLACKLIST");
        assertThat(audit.get("target_id")).isEqualTo("SHA256:" + hash);
        assertThat(audit.get("actor_id").toString()).isEqualTo(adminApi.adminId().toString());
    }

    @Test
    void 로그인하지_않으면_401이다() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                URL + "/" + newHash(), HttpMethod.DELETE, HttpEntity.EMPTY, mapType());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code")).isEqualTo("UNAUTHORIZED");
    }
}