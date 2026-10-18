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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
class AdminAuthRestIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";
    // application.yml의 CORS_ALLOWED_ORIGINS 기본값
    private static final String ALLOWED_ORIGIN = "http://localhost:5173";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String seedAdmin(boolean active) {
        String username = "admin-" + UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO admin_users (admin_id, username, password_hash, role, is_active)
                VALUES (?, ?, ?, 'ANALYST', ?)
                """,
                UUID.randomUUID(), username, passwordEncoder.encode(PASSWORD), active);
        return username;
    }

    private UUID adminIdOf(String username) {
        return jdbcTemplate.queryForObject(
                "SELECT admin_id FROM admin_users WHERE username = ?", UUID.class, username);
    }

    private ResponseEntity<Map<String, Object>> post(String path, String json, HttpHeaders headers) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(json, headers), mapType());
    }

    private ResponseEntity<Map<String, Object>> login(String username, String password, HttpHeaders headers) {
        return post("/api/admin/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}", headers);
    }

    private ResponseEntity<Map<String, Object>> login(String username, String password) {
        return login(username, password, new HttpHeaders());
    }

    private ResponseEntity<Map<String, Object>> get(String path, String sessionCookie) {
        HttpHeaders headers = new HttpHeaders();
        if (sessionCookie != null) {
            headers.add(HttpHeaders.COOKIE, sessionCookie);
        }
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), mapType());
    }

    private static HttpHeaders withCookie(String sessionCookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, sessionCookie);
        return headers;
    }

    /** Set-Cookie 중 세션 쿠키 원문 한 줄. */
    private static String rawSessionCookie(ResponseEntity<?> response) {
        List<String> cookies = response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE);
        return cookies.stream()
                .filter(c -> c.startsWith("JSESSIONID="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("세션 쿠키가 없다: " + cookies));
    }

    /** 다음 요청의 Cookie 헤더에 넣을 "JSESSIONID=..." 부분. */
    private static String sessionCookie(ResponseEntity<?> response) {
        return rawSessionCookie(response).split(";", 2)[0];
    }

    @SuppressWarnings("unchecked")
    private static Class<Map<String, Object>> mapType() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }

    @Test
    void 로그인에_성공하면_세션_쿠키를_주고_그_쿠키로_me를_조회한다() {
        String username = seedAdmin(true);

        ResponseEntity<Map<String, Object>> response = login(username, PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("adminId", adminIdOf(username).toString())
                .containsEntry("username", username)
                .containsEntry("role", "ANALYST")
                .containsEntry("isActive", true)
                .containsKey("lastLoginAt")
                .doesNotContainKeys("passwordHash", "password");

        ResponseEntity<Map<String, Object>> me = get("/api/admin/auth/me", sessionCookie(response));
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody())
                .containsEntry("adminId", adminIdOf(username).toString())
                .containsEntry("username", username)
                .containsEntry("role", "ANALYST")
                .containsEntry("isActive", true)
                .doesNotContainKeys("passwordHash", "password");
    }

    @Test
    void 세션_쿠키는_운영_기본값으로_HttpOnly_Secure_SameSite_None이다() {
        String username = seedAdmin(true);

        String cookie = rawSessionCookie(login(username, PASSWORD));

        assertThat(cookie).containsIgnoringCase("HttpOnly")
                .containsIgnoringCase("Secure")
                .containsIgnoringCase("SameSite=None");
    }

    @Test
    void 로그인하면_last_login_at이_갱신된다() {
        String username = seedAdmin(true);
        Instant before = Instant.now().minusSeconds(1);

        ResponseEntity<Map<String, Object>> response = login(username, PASSWORD);

        Timestamp lastLoginAt = jdbcTemplate.queryForObject(
                "SELECT last_login_at FROM admin_users WHERE username = ?", Timestamp.class, username);
        assertThat(lastLoginAt).isNotNull();
        assertThat(lastLoginAt.toInstant()).isAfter(before);
        assertThat(Instant.parse((String) response.getBody().get("lastLoginAt"))).isAfter(before);
    }

    @Test
    void 로그인하면_LOGIN_감사_로그를_본인과_X_Forwarded_For의_첫_주소로_남긴다() {
        String username = seedAdmin(true);
        UUID adminId = adminIdOf(username);
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Forwarded-For", "203.0.113.7, 10.0.0.2");

        login(username, PASSWORD, headers);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT actor_id, target_type, host(ip_address) AS ip FROM audit_logs
                WHERE action = 'LOGIN' AND target_id = ?
                """,
                adminId.toString());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("actor_id")).isEqualTo(adminId);
        assertThat(rows.get(0).get("target_type")).isEqualTo("ADMIN_USER");
        assertThat(rows.get(0).get("ip")).isEqualTo("203.0.113.7");
    }

    @Test
    void 틀린_비밀번호_없는_계정_비활성_계정은_모두_같은_401을_준다() {
        String active = seedAdmin(true);
        String inactive = seedAdmin(false);

        ResponseEntity<Map<String, Object>> wrongPassword = login(active, "wrong-password");
        ResponseEntity<Map<String, Object>> unknownUser = login("nobody-" + UUID.randomUUID(), PASSWORD);
        ResponseEntity<Map<String, Object>> inactiveUser = login(inactive, PASSWORD);

        for (ResponseEntity<Map<String, Object>> response : List.of(wrongPassword, unknownUser, inactiveUser)) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody().get("code")).isEqualTo("UNAUTHORIZED");
            assertThat(response.getBody().get("path")).isEqualTo("/api/admin/auth/login");
            assertThat(response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE)).isEmpty();
        }
        assertThat(unknownUser.getBody().get("message")).isEqualTo(wrongPassword.getBody().get("message"));
        assertThat(inactiveUser.getBody().get("message")).isEqualTo(wrongPassword.getBody().get("message"));
    }

    @Test
    void 로그인_실패는_시도한_계정명으로_LOGIN_FAILED를_남기고_비밀번호는_남기지_않는다() {
        String username = seedAdmin(true);
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Forwarded-For", "198.51.100.4");

        login(username, "guessed-secret-123", headers);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT actor_id, detail, host(ip_address) AS ip FROM audit_logs
                WHERE action = 'LOGIN_FAILED' AND target_type = 'ADMIN_USER' AND target_id = ?
                """,
                username);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("actor_id")).isNull();
        assertThat(rows.get(0).get("ip")).isEqualTo("198.51.100.4");
        assertThat((String) rows.get(0).get("detail"))
                .contains("\"attempted_username\":\"" + username + "\"")
                .contains("BAD_PASSWORD")
                .doesNotContain("guessed-secret-123");
    }

    @Test
    void 아이디나_비밀번호가_비면_400과_필드별_사유를_준다() {
        ResponseEntity<Map<String, Object>> response = post("/api/admin/auth/login",
                "{\"username\":\" \",\"password\":\"\"}", new HttpHeaders());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_INPUT");
        List<Map<String, Object>> fieldErrors = (List<Map<String, Object>>) response.getBody().get("fieldErrors");
        assertThat(fieldErrors).extracting(e -> e.get("field")).contains("username", "password");
    }

    @Test
    void 로그인하면_세션_ID를_새로_발급하고_이전_세션은_쓸_수_없다() {
        String username = seedAdmin(true);
        String first = sessionCookie(login(username, PASSWORD));
        // 세션 쿠키를 실은 쓰기 요청이라 브라우저처럼 Origin을 싣는다(없으면 OriginCheckFilter가 막는다).
        HttpHeaders headers = withCookie(first);
        headers.add(HttpHeaders.ORIGIN, ALLOWED_ORIGIN);

        String second = sessionCookie(login(username, PASSWORD, headers));

        assertThat(second).isNotEqualTo(first);
        assertThat(get("/api/admin/auth/me", first).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/admin/auth/me", second).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void 로그인하지_않고_me를_부르면_401_ErrorResponse다() {
        ResponseEntity<Map<String, Object>> response = get("/api/admin/auth/me", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody())
                .containsEntry("status", 401)
                .containsEntry("code", "UNAUTHORIZED")
                .containsEntry("path", "/api/admin/auth/me")
                .containsKeys("timestamp", "message", "fieldErrors");
    }

    @Test
    void 로그인하지_않으면_다른_관리자_경로도_404나_500이_아니라_401이다() {
        ResponseEntity<Map<String, Object>> response = get("/api/admin/agents", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code")).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void 로그인하면_없는_관리자_경로는_404다() {
        String cookie = sessionCookie(login(seedAdmin(true), PASSWORD));

        ResponseEntity<Map<String, Object>> response = get("/api/admin/agents", cookie);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("NOT_FOUND");
    }

    @Test
    void 다운로드_이벤트_목록은_시연용이라_로그인_없이_열린다() {
        ResponseEntity<Map<String, Object>> response = get("/api/admin/download-events", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void 헬스체크는_로그인_없이_열린다() {
        ResponseEntity<Map<String, Object>> response = get("/actuator/health", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void 로그아웃하면_세션이_끊기고_LOGOUT_감사_로그가_남는다() {
        String username = seedAdmin(true);
        String cookie = sessionCookie(login(username, PASSWORD));
        HttpHeaders headers = withCookie(cookie);
        headers.add(HttpHeaders.ORIGIN, ALLOWED_ORIGIN);

        ResponseEntity<Map<String, Object>> response = post("/api/admin/auth/logout", "", headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(get("/api/admin/auth/me", cookie).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        Integer logouts = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'LOGOUT' AND actor_id = ? AND target_id = ?",
                Integer.class, adminIdOf(username), adminIdOf(username).toString());
        assertThat(logouts).isEqualTo(1);
    }

    @Test
    void 로그인_후_계정이_비활성화되면_me는_401이다() {
        String username = seedAdmin(true);
        String cookie = sessionCookie(login(username, PASSWORD));
        jdbcTemplate.update("UPDATE admin_users SET is_active = false WHERE username = ?", username);

        ResponseEntity<Map<String, Object>> response = get("/api/admin/auth/me", cookie);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code")).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void 허용되지_않은_Origin의_쓰기_요청은_403이다() {
        String username = seedAdmin(true);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.ORIGIN, "https://evil.example.com");

        ResponseEntity<Map<String, Object>> response = login(username, PASSWORD, headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody())
                .containsEntry("code", "FORBIDDEN")
                .containsEntry("path", "/api/admin/auth/login");
        assertThat(response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE)).isEmpty();
    }

    @Test
    void 허용된_Origin의_쓰기_요청은_통과한다() {
        String username = seedAdmin(true);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.ORIGIN, ALLOWED_ORIGIN);

        ResponseEntity<Map<String, Object>> response = login(username, PASSWORD, headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo(ALLOWED_ORIGIN);
        assertThat(response.getHeaders().getAccessControlAllowCredentials()).isTrue();
    }

    @Test
    void Origin_없이_세션_쿠키를_실은_쓰기_요청은_403이다() {
        String username = seedAdmin(true);
        String cookie = sessionCookie(login(username, PASSWORD));

        ResponseEntity<Map<String, Object>> response = post("/api/admin/auth/logout", "", withCookie(cookie));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo("FORBIDDEN");
        // 거부된 요청은 세션을 건드리지 않았어야 한다
        assertThat(get("/api/admin/auth/me", cookie).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
