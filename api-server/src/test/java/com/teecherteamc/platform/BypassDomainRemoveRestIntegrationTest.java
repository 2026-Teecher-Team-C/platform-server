package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

// 로그인이 필요한 API라 AdminApiClient로 로그인한 access 토큰으로 요청한다. ADMIN 권한 게이트는 범위 밖.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfiguration.class, AdminApiClient.class})
class BypassDomainRemoveRestIntegrationTest {

    private static final String URL = "/api/admin/bypass-domains";
    private static final Instant SEEDED_AT = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    private AdminApiClient adminClient;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 테이블을_비운다() {
        jdbcTemplate.update("DELETE FROM bypass_domains");
    }

    private UUID seed(String host, String category, boolean active) {
        UUID bypassId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO bypass_domains (bypass_id, domain_pattern, category, reason, is_active, created_at)
                VALUES (?, ?, ?, '예전 사유', ?, ?)
                """,
                bypassId, host, category, active, Timestamp.from(SEEDED_AT));
        return bypassId;
    }

    private ResponseEntity<String> delete(String bypassId) {
        return adminClient.exchange(URL + "/" + bypassId, HttpMethod.DELETE, null, String.class);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map<String, Object>> list(String query) {
        return adminClient.getForEntity(URL + query, (Class<Map<String, Object>>) (Class<?>) Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> hosts(ResponseEntity<Map<String, Object>> response) {
        List<Map<String, Object>> content = (List<Map<String, Object>>) response.getBody().get("content");
        return content.stream().map(row -> row.get("host")).toList();
    }

    private List<Map<String, Object>> audits(UUID bypassId) {
        return jdbcTemplate.queryForList(
                """
                SELECT actor_id, target_type, detail::jsonb ->> 'host' AS host,
                       detail::jsonb ->> 'category' AS category, detail::jsonb ->> 'reason' AS reason
                FROM audit_logs WHERE action = 'REMOVE_BYPASS_DOMAIN' AND target_id = ?
                """,
                bypassId.toString());
    }

    @Test
    void 해제하면_204이고_행은_남아_비활성이_된다() {
        UUID bypassId = seed("remove.vendor.com", "PINNED", true);

        ResponseEntity<String> response = delete(bypassId.toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT is_active, category, reason, created_at FROM bypass_domains WHERE bypass_id = ?", bypassId);
        assertThat(row.get("is_active")).isEqualTo(false);
        // 과거 정보는 감사 추적을 위해 그대로 남는다.
        assertThat(row.get("category")).isEqualTo("PINNED");
        assertThat(row.get("reason")).isEqualTo("예전 사유");
        assertThat(((Timestamp) row.get("created_at")).toInstant()).isEqualTo(SEEDED_AT);
    }

    @Test
    void 해제한_도메인은_기본_목록에서_빠지고_비활성_목록에는_나온다() {
        UUID bypassId = seed("gone.vendor.com", "PINNED", true);
        seed("stay.vendor.com", "SECURITY_UPDATE", true);

        delete(bypassId.toString());

        assertThat(hosts(list(""))).containsExactly("stay.vendor.com");
        assertThat(hosts(list("?isActive=false"))).containsExactly("gone.vendor.com");
    }

    @Test
    void 감사_로그에_해제한_사람과_해제_전_값을_남긴다() {
        UUID bypassId = seed("audit.vendor.com", "SECURITY_UPDATE", true);

        delete(bypassId.toString());

        List<Map<String, Object>> logs = audits(bypassId);
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).get("actor_id")).isEqualTo(adminClient.adminId());
        assertThat(logs.get(0).get("target_type")).isEqualTo("BYPASS_DOMAIN");
        assertThat(logs.get(0).get("host")).isEqualTo("audit.vendor.com");
        assertThat(logs.get(0).get("category")).isEqualTo("SECURITY_UPDATE");
        assertThat(logs.get(0).get("reason")).isEqualTo("예전 사유");
    }

    @Test
    void 이미_해제된_id는_404이고_감사_로그가_늘지_않는다() {
        UUID bypassId = seed("inactive.vendor.com", "PINNED", false);

        ResponseEntity<String> response = delete(bypassId.toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(audits(bypassId)).isEmpty();
    }

    @Test
    void 없는_id는_404이다() {
        ResponseEntity<String> response = delete(UUID.randomUUID().toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void UUID_형식이_아닌_id는_400이다() {
        assertThat(delete("not-a-uuid").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 로그인하지_않으면_401이고_해제되지_않는다() {
        UUID bypassId = seed("anon.vendor.com", "PINNED", true);

        ResponseEntity<String> response = restTemplate.exchange(
                URL + "/" + bypassId, HttpMethod.DELETE, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        Boolean active = jdbcTemplate.queryForObject(
                "SELECT is_active FROM bypass_domains WHERE bypass_id = ?", Boolean.class, bypassId);
        assertThat(active).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 등록_해제_재등록하면_같은_행이_되살아난다() {
        Map<String, Object> body = new HashMap<>();
        body.put("host", "cycle.vendor.com");
        body.put("category", "PINNED");
        body.put("reason", "처음");
        Class<Map<String, Object>> mapType = (Class<Map<String, Object>>) (Class<?>) Map.class;
        String bypassId = (String) adminClient.postForEntity(URL, body, mapType).getBody().get("bypassId");

        assertThat(delete(bypassId).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        body.put("category", "SECURITY_UPDATE");
        body.put("reason", "다시");
        ResponseEntity<Map<String, Object>> again = adminClient.postForEntity(URL, body, mapType);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(again.getBody().get("bypassId")).isEqualTo(bypassId);
        assertThat(again.getBody().get("isActive")).isEqualTo(true);
        assertThat(again.getBody().get("category")).isEqualTo("SECURITY_UPDATE");
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bypass_domains WHERE domain_pattern = 'cycle.vendor.com'", Integer.class);
        assertThat(rows).isEqualTo(1);
    }
}
