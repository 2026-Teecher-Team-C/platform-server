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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

// 다른 테스트(로그인 등)도 audit_logs에 행을 남기므로 테이블을 비우지 않고, 테스트마다 새 행위자를 만들어 actorId로 거른다.
// 로그인이 필요한 API라 AdminApiClient로 요청한다. ADMIN 권한 게이트는 범위 밖.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfiguration.class, AdminApiClient.class})
class AuditLogListRestIntegrationTest {

    private static final String URL = "/api/admin/audit-logs";
    private static final Instant T1 = Instant.parse("2026-10-20T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-21T00:00:00Z");
    private static final Instant T3 = Instant.parse("2026-10-22T00:00:00Z");

    @Autowired
    private AdminApiClient adminClient;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map<String, Object>> list(String query) {
        return adminClient.getForEntity(URL + query, (Class<Map<String, Object>>) (Class<?>) Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> content(ResponseEntity<Map<String, Object>> response) {
        return (List<Map<String, Object>>) response.getBody().get("content");
    }

    private static List<Object> actions(ResponseEntity<Map<String, Object>> response) {
        return content(response).stream().map(row -> row.get("action")).toList();
    }

    private UUID seedAdmin(String role) {
        UUID adminId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO admin_users (admin_id, username, password_hash, role) VALUES (?, ?, 'unused', ?)",
                adminId, "audit-" + adminId, role);
        return adminId;
    }

    private void seedLog(UUID actorId, String action, String targetType, String targetId, String detail,
                         String ip, Instant createdAt) {
        jdbcTemplate.update(
                """
                INSERT INTO audit_logs (actor_id, action, target_type, target_id, detail, ip_address, created_at)
                VALUES (?, ?, ?, ?, ?, CAST(? AS inet), ?)
                """,
                actorId, action, targetType, targetId, detail, ip, Timestamp.from(createdAt));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 최신순_PageResponse이고_행위자와_detail_객체를_내려준다() {
        UUID actor = seedAdmin("ANALYST");
        seedLog(actor, "ADD_BLACKLIST", "BLACKLIST", "SHA256:" + "a".repeat(64), null, null, T1);
        seedLog(actor, "ADD_WHITELIST", "WHITELIST", "SHA256:" + "b".repeat(64),
                "{\"after\":{\"reason\":\"사내 배포 도구\"}}", "203.0.113.10", T2);

        ResponseEntity<Map<String, Object>> response = list("?actorId=" + actor);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKeys("content", "page", "size", "totalElements", "totalPages");
        assertThat(response.getBody().get("totalElements")).isEqualTo(2);
        assertThat(actions(response)).containsExactly("ADD_WHITELIST", "ADD_BLACKLIST");

        Map<String, Object> first = content(response).get(0);
        assertThat(first.get("logId")).isNotNull();
        assertThat(first.get("dangerous")).isEqualTo(true);
        assertThat(first.get("targetType")).isEqualTo("WHITELIST");
        assertThat(first.get("targetId")).isEqualTo("SHA256:" + "b".repeat(64));
        assertThat(first.get("ipAddress")).isEqualTo("203.0.113.10");
        assertThat(first.get("createdAt")).isEqualTo("2026-10-21T00:00:00Z");
        Map<String, Object> actorJson = (Map<String, Object>) first.get("actor");
        assertThat(actorJson.get("adminId")).isEqualTo(actor.toString());
        assertThat(actorJson.get("username")).isEqualTo("audit-" + actor);
        assertThat(actorJson.get("role")).isEqualTo("ANALYST");
        Map<String, Object> detail = (Map<String, Object>) first.get("detail");
        assertThat(((Map<String, Object>) detail.get("after")).get("reason")).isEqualTo("사내 배포 도구");

        Map<String, Object> second = content(response).get(1);
        assertThat(second.get("dangerous")).isEqualTo(false);
        assertThat(second.get("detail")).isNull();
        assertThat(second.get("ipAddress")).isNull();
    }

    @Test
    void 행위자가_없는_로그는_actor가_null이다() {
        String attempted = "nobody-" + UUID.randomUUID();
        seedLog(null, "LOGIN_FAILED", "ADMIN_USER", attempted, null, null, T1);

        ResponseEntity<Map<String, Object>> response = list("?action=LOGIN_FAILED&size=200");

        Map<String, Object> row = content(response).stream()
                .filter(r -> attempted.equals(r.get("targetId")))
                .findFirst().orElseThrow();
        assertThat(row.get("actor")).isNull();
    }

    @Test
    void action과_targetType으로_거른다() {
        UUID actor = seedAdmin("ADMIN");
        seedLog(actor, "ADD_WHITELIST", "WHITELIST", "SHA256:" + "c".repeat(64), null, null, T1);
        seedLog(actor, "REMOVE_WHITELIST", "WHITELIST", "SHA256:" + "c".repeat(64), null, null, T2);
        seedLog(actor, "ADD_BLACKLIST", "BLACKLIST", "SHA256:" + "d".repeat(64), null, null, T3);

        assertThat(actions(list("?actorId=" + actor + "&action=REMOVE_WHITELIST")))
                .containsExactly("REMOVE_WHITELIST");
        assertThat(actions(list("?actorId=" + actor + "&targetType=WHITELIST")))
                .containsExactly("REMOVE_WHITELIST", "ADD_WHITELIST");
    }

    @Test
    void dangerousOnly면_검사를_약하게_만드는_조작만_나온다() {
        UUID actor = seedAdmin("ADMIN");
        seedLog(actor, "ADD_WHITELIST", "WHITELIST", "SHA256:" + "e".repeat(64), null, null, T1);
        seedLog(actor, "REMOVE_BLACKLIST", "BLACKLIST", "SHA256:" + "e".repeat(64), null, null, T1);
        seedLog(actor, "RESTORE_QUARANTINE", "QUARANTINE", UUID.randomUUID().toString(), null, null, T2);
        seedLog(actor, "ADD_BYPASS_DOMAIN", "BYPASS_DOMAIN", UUID.randomUUID().toString(), null, null, T2);
        seedLog(actor, "ADD_BLACKLIST", "BLACKLIST", "SHA256:" + "f".repeat(64), null, null, T3);
        seedLog(actor, "LOGIN", "ADMIN_USER", actor.toString(), null, null, T3);

        ResponseEntity<Map<String, Object>> response = list("?actorId=" + actor + "&dangerousOnly=true");

        assertThat(actions(response)).containsExactlyInAnyOrder(
                "ADD_WHITELIST", "REMOVE_BLACKLIST", "RESTORE_QUARANTINE", "ADD_BYPASS_DOMAIN");
        assertThat(response.getBody().get("totalElements")).isEqualTo(4);
    }

    @Test
    void from은_포함_to는_제외로_기간을_거른다() {
        UUID actor = seedAdmin("ADMIN");
        seedLog(actor, "LOGIN", "ADMIN_USER", actor.toString(), null, null, T1);
        seedLog(actor, "LOGOUT", "ADMIN_USER", actor.toString(), null, null, T2);
        seedLog(actor, "ADD_BLACKLIST", "BLACKLIST", "SHA256:" + "1".repeat(64), null, null, T3);

        assertThat(actions(list("?actorId=" + actor + "&from=2026-10-21T00:00:00Z&to=2026-10-22T00:00:00Z")))
                .containsExactly("LOGOUT");
    }

    @Test
    void 페이지를_나눠서_내려준다() {
        UUID actor = seedAdmin("ADMIN");
        seedLog(actor, "LOGIN", "ADMIN_USER", actor.toString(), null, null, T1);
        seedLog(actor, "ADD_BLACKLIST", "BLACKLIST", "SHA256:" + "2".repeat(64), null, null, T2);
        seedLog(actor, "LOGOUT", "ADMIN_USER", actor.toString(), null, null, T3);

        ResponseEntity<Map<String, Object>> response = list("?actorId=" + actor + "&page=1&size=2");

        assertThat(actions(response)).containsExactly("LOGIN");
        assertThat(response.getBody().get("totalElements")).isEqualTo(3);
        assertThat(response.getBody().get("totalPages")).isEqualTo(2);
    }

    @Test
    void JSON이_아닌_detail은_문자열_그대로_내려준다() {
        UUID actor = seedAdmin("ADMIN");
        seedLog(actor, "INVALIDATE_CACHE", "CACHE", null, "not-json", null, T1);

        assertThat(content(list("?actorId=" + actor)).get(0).get("detail")).isEqualTo("not-json");
    }

    @Test
    void 빈_문자열_필터는_걸지_않은_것과_같다() {
        UUID actor = seedAdmin("ADMIN");
        seedLog(actor, "LOGIN", "ADMIN_USER", actor.toString(), null, null, T1);

        ResponseEntity<Map<String, Object>> response =
                list("?actorId=" + actor + "&action=&targetType=&from=&to=");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actions(response)).containsExactly("LOGIN");
    }

    @Test
    void 잘못된_파라미터는_400_INVALID_INPUT이다() {
        List<String> badQueries = List.of(
                "?actorId=not-uuid",
                "?action=DROP_TABLE",
                "?targetType=NOTHING",
                "?from=yesterday",
                "?to=2026-13-01",
                "?from=2026-10-22T00:00:00Z&to=2026-10-21T00:00:00Z",
                "?dangerousOnly=abc",
                "?page=-1",
                "?size=0",
                "?size=201");

        for (String query : badQueries) {
            ResponseEntity<Map<String, Object>> response = list(query);
            assertThat(response.getStatusCode()).as(query).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().get("code")).as(query).isEqualTo("INVALID_INPUT");
        }
    }

    @Test
    void 토큰이_없으면_401() {
        ResponseEntity<String> response = restTemplate.getForEntity(URL, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
