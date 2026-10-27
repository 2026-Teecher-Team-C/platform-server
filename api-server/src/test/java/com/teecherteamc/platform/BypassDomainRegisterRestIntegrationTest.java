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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

// 로그인이 필요한 API라 AdminApiClient로 로그인한 access 토큰으로 요청한다. ADMIN 권한 게이트는 범위 밖.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfiguration.class, AdminApiClient.class})
class BypassDomainRegisterRestIntegrationTest {

    private static final String URL = "/api/admin/bypass-domains";
    private static final Instant SEEDED_AT = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    private AdminApiClient adminClient;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 목록 테스트와 같은 이유로 매번 비운다. host가 UNIQUE라 테스트끼리 서로의 행에 걸리면 안 된다.
    @BeforeEach
    void 테이블을_비운다() {
        jdbcTemplate.update("DELETE FROM bypass_domains");
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map<String, Object>> create(String host, String category, String reason) {
        Map<String, Object> body = new HashMap<>();
        body.put("host", host);
        body.put("category", category);
        body.put("reason", reason);
        return adminClient.postForEntity(URL, body, (Class<Map<String, Object>>) (Class<?>) Map.class);
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

    private int countRows(String host) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bypass_domains WHERE domain_pattern = ?", Integer.class, host);
    }

    private List<Map<String, Object>> audits(String bypassId) {
        return jdbcTemplate.queryForList(
                """
                SELECT actor_id, target_type, detail::jsonb ->> 'host' AS host,
                       detail::jsonb ->> 'category' AS category, detail::jsonb ->> 'reason' AS reason
                FROM audit_logs WHERE action = 'ADD_BYPASS_DOMAIN' AND target_id = ?
                """,
                bypassId);
    }

    @Test
    void 등록하면_201과_항목을_돌려주고_host는_정규화된다() {
        ResponseEntity<Map<String, Object>> response = create("  Update.Vendor.COM. ", "PINNED", "인증서 피닝");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("bypassId")).isNotNull();
        assertThat(body.get("host")).isEqualTo("update.vendor.com");
        assertThat(body.get("category")).isEqualTo("PINNED");
        assertThat(body.get("reason")).isEqualTo("인증서 피닝");
        assertThat(body.get("isActive")).isEqualTo(true);
        assertThat(body.get("createdAt")).isNotNull();
        assertThat(countRows("update.vendor.com")).isEqualTo(1);
    }

    @Test
    void 등록자를_행에_남기고_감사_로그를_기록한다() {
        ResponseEntity<Map<String, Object>> response = create("audit.vendor.com", "SECURITY_UPDATE", "보안 업데이트");

        String bypassId = (String) response.getBody().get("bypassId");
        UUID createdBy = jdbcTemplate.queryForObject(
                "SELECT created_by FROM bypass_domains WHERE bypass_id = ?::uuid", UUID.class, bypassId);
        assertThat(createdBy).isEqualTo(adminClient.adminId());

        List<Map<String, Object>> logs = audits(bypassId);
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).get("actor_id")).isEqualTo(adminClient.adminId());
        assertThat(logs.get(0).get("target_type")).isEqualTo("BYPASS_DOMAIN");
        assertThat(logs.get(0).get("host")).isEqualTo("audit.vendor.com");
        assertThat(logs.get(0).get("category")).isEqualTo("SECURITY_UPDATE");
        assertThat(logs.get(0).get("reason")).isEqualTo("보안 업데이트");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 등록한_도메인은_목록에_나온다() {
        create("listed.vendor.com", "PINNED", "목록 확인");

        ResponseEntity<Map<String, Object>> list = adminClient.getForEntity(
                URL, (Class<Map<String, Object>>) (Class<?>) Map.class);

        List<Map<String, Object>> content = (List<Map<String, Object>>) list.getBody().get("content");
        assertThat(content).extracting(row -> row.get("host")).containsExactly("listed.vendor.com");
    }

    @Test
    void 이미_활성인_host는_409이고_행도_감사_로그도_늘지_않는다() {
        UUID existing = seed("dup.vendor.com", "PINNED", true);

        // 대소문자만 다른 같은 host도 같은 것으로 본다.
        ResponseEntity<Map<String, Object>> response = create("DUP.vendor.com", "SECURITY_UPDATE", "중복");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(countRows("dup.vendor.com")).isEqualTo(1);
        assertThat(audits(existing.toString())).isEmpty();
        String reason = jdbcTemplate.queryForObject(
                "SELECT reason FROM bypass_domains WHERE bypass_id = ?", String.class, existing);
        assertThat(reason).isEqualTo("예전 사유");
    }

    @Test
    void 해제했던_host를_다시_등록하면_새_행이_아니라_같은_행을_되살린다() {
        UUID existing = seed("again.vendor.com", "PINNED", false);

        ResponseEntity<Map<String, Object>> response = create("again.vendor.com", "SECURITY_UPDATE", "다시 켬");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("bypassId")).isEqualTo(existing.toString());
        assertThat(body.get("isActive")).isEqualTo(true);
        assertThat(body.get("category")).isEqualTo("SECURITY_UPDATE");
        assertThat(body.get("reason")).isEqualTo("다시 켬");
        assertThat(countRows("again.vendor.com")).isEqualTo(1);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT created_by, created_at FROM bypass_domains WHERE bypass_id = ?", existing);
        assertThat(row.get("created_by")).isEqualTo(adminClient.adminId());
        // 최초 등록 시각은 그대로 남는다.
        assertThat(((Timestamp) row.get("created_at")).toInstant()).isEqualTo(SEEDED_AT);
        assertThat(audits(existing.toString())).hasSize(1);
    }

    @Test
    void 잘못된_host는_400이고_저장하지_않는다() {
        for (String host : List.of("*.google.com", "https://a.com/x", "localhost", "1.2.3.4", "a_b.com", "a.com:443")) {
            assertThat(create(host, "PINNED", "사유").getStatusCode())
                    .as(host).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        Integer rows = jdbcTemplate.queryForObject("SELECT count(*) FROM bypass_domains", Integer.class);
        assertThat(rows).isZero();
    }

    @Test
    void 모르는_category는_400이다() {
        assertThat(create("cat.vendor.com", "OTHER", "사유").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // 소문자도 허용하지 않는다. (목록 API의 category 필터와 같은 기준)
        assertThat(create("cat.vendor.com", "pinned", "사유").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 비어_있는_필수_값은_400이다() {
        assertThat(create(null, "PINNED", "사유").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(create("blank.vendor.com", null, "사유").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(create("blank.vendor.com", "PINNED", "  ").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(countRows("blank.vendor.com")).isZero();
    }

    @Test
    void 로그인하지_않으면_401이다() {
        Map<String, Object> body = Map.of("host", "anon.vendor.com", "category", "PINNED", "reason", "사유");

        ResponseEntity<String> response = restTemplate.postForEntity(URL, body, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(countRows("anon.vendor.com")).isZero();
    }
}
