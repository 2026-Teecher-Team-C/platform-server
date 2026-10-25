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
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

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

    @Autowired
    private JwtEncoder jwtEncoder;

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

    private ResponseEntity<Map<String, Object>> get(String path, String accessToken) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(bearer(accessToken)), mapType());
    }

    private static HttpHeaders bearer(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        if (accessToken != null) {
            headers.setBearerAuth(accessToken);
        }
        return headers;
    }

    private ResponseEntity<Map<String, Object>> refresh(String refreshToken) {
        return post("/api/admin/auth/refresh", "{\"refreshToken\":\"" + refreshToken + "\"}", new HttpHeaders());
    }

    private static String accessToken(ResponseEntity<Map<String, Object>> response) {
        return (String) response.getBody().get("accessToken");
    }

    private static String refreshToken(ResponseEntity<Map<String, Object>> response) {
        return (String) response.getBody().get("refreshToken");
    }

    @SuppressWarnings("unchecked")
    private static Class<Map<String, Object>> mapType() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }

    @Test
    void 로그인에_성공하면_access와_refresh_토큰을_Bearer로_준다() {
        String username = seedAdmin(true);

        ResponseEntity<Map<String, Object>> response = login(username, PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("tokenType", "Bearer")
                .containsKeys("accessToken", "refreshToken")
                .doesNotContainKeys("passwordHash", "password");
        assertThat(accessToken(response)).isNotBlank().isNotEqualTo(refreshToken(response));
        assertThat(refreshToken(response)).isNotBlank();
        // 쿠키는 쓰지 않는다
        assertThat(response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE)).isEmpty();
    }

    @Test
    void access_토큰으로_me를_조회한다() {
        String username = seedAdmin(true);
        String accessToken = accessToken(login(username, PASSWORD));

        ResponseEntity<Map<String, Object>> me = get("/api/admin/auth/me", accessToken);

        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody())
                .containsEntry("adminId", adminIdOf(username).toString())
                .containsEntry("username", username)
                .containsEntry("role", "ANALYST")
                .containsEntry("isActive", true)
                .containsKey("lastLoginAt")
                .doesNotContainKeys("passwordHash", "password");
    }

    @Test
    void 로그인하면_last_login_at이_갱신된다() {
        String username = seedAdmin(true);
        Instant before = Instant.now().minusSeconds(1);

        login(username, PASSWORD);

        Timestamp lastLoginAt = jdbcTemplate.queryForObject(
                "SELECT last_login_at FROM admin_users WHERE username = ?", Timestamp.class, username);
        assertThat(lastLoginAt).isNotNull();
        assertThat(lastLoginAt.toInstant()).isAfter(before);
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
            assertThat(response.getBody()).doesNotContainKeys("accessToken", "refreshToken");
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
    void 서명이_틀린_토큰은_401_ErrorResponse다() {
        String accessToken = accessToken(login(seedAdmin(true), PASSWORD));
        String tampered = accessToken.substring(0, accessToken.length() - 2)
                + (accessToken.endsWith("AA") ? "BB" : "AA");

        ResponseEntity<Map<String, Object>> response = get("/api/admin/auth/me", tampered);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code")).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void 만료된_access_토큰은_401이다() {
        UUID adminId = adminIdOf(seedAdmin(true));
        Instant issuedAt = Instant.now().minusSeconds(7200);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(adminId.toString())
                .claim("username", "whoever")
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(60))
                .build();
        String expired = jwtEncoder.encode(
                JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

        ResponseEntity<Map<String, Object>> response = get("/api/admin/auth/me", expired);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code")).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void refresh_토큰은_access_토큰으로_쓸_수_없다() {
        String refreshToken = refreshToken(login(seedAdmin(true), PASSWORD));

        ResponseEntity<Map<String, Object>> response = get("/api/admin/auth/me", refreshToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 로그인하지_않으면_다른_관리자_경로도_404나_500이_아니라_401이다() {
        ResponseEntity<Map<String, Object>> response = get("/api/admin/agents", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code")).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void 로그인하면_없는_관리자_경로는_404다() {
        String accessToken = accessToken(login(seedAdmin(true), PASSWORD));

        ResponseEntity<Map<String, Object>> response = get("/api/admin/agents", accessToken);

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
    void refresh하면_새_access와_refresh_토큰을_주고_새_access로_me를_조회한다() {
        String username = seedAdmin(true);
        ResponseEntity<Map<String, Object>> loggedIn = login(username, PASSWORD);

        ResponseEntity<Map<String, Object>> refreshed = refresh(refreshToken(loggedIn));

        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refreshed.getBody()).containsEntry("tokenType", "Bearer");
        assertThat(refreshToken(refreshed)).isNotBlank().isNotEqualTo(refreshToken(loggedIn));
        ResponseEntity<Map<String, Object>> me = get("/api/admin/auth/me", accessToken(refreshed));
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody()).containsEntry("username", username);
    }

    @Test
    void refresh_토큰은_한_번만_쓸_수_있다() {
        String refreshToken = refreshToken(login(seedAdmin(true), PASSWORD));
        refresh(refreshToken);

        ResponseEntity<Map<String, Object>> reused = refresh(refreshToken);

        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(reused.getBody().get("code")).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void 모르는_refresh_토큰은_401이다() {
        ResponseEntity<Map<String, Object>> response = refresh("not-a-real-refresh-token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code")).isEqualTo("UNAUTHORIZED");
        assertThat(response.getBody().get("path")).isEqualTo("/api/admin/auth/refresh");
    }

    @Test
    void refresh_토큰이_비면_400이다() {
        ResponseEntity<Map<String, Object>> response = post("/api/admin/auth/refresh",
                "{\"refreshToken\":\"\"}", new HttpHeaders());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_INPUT");
    }

    @Test
    void 비활성화된_계정은_refresh할_수_없다() {
        String username = seedAdmin(true);
        String refreshToken = refreshToken(login(username, PASSWORD));
        jdbcTemplate.update("UPDATE admin_users SET is_active = false WHERE username = ?", username);

        ResponseEntity<Map<String, Object>> response = refresh(refreshToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 로그아웃하면_refresh_토큰을_폐기하고_LOGOUT_감사_로그가_남는다() {
        String username = seedAdmin(true);
        ResponseEntity<Map<String, Object>> loggedIn = login(username, PASSWORD);

        ResponseEntity<Map<String, Object>> response = post("/api/admin/auth/logout",
                "{\"refreshToken\":\"" + refreshToken(loggedIn) + "\"}", bearer(accessToken(loggedIn)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(refresh(refreshToken(loggedIn)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        Integer logouts = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'LOGOUT' AND actor_id = ? AND target_id = ?",
                Integer.class, adminIdOf(username), adminIdOf(username).toString());
        assertThat(logouts).isEqualTo(1);
    }

    @Test
    void 남의_refresh_토큰으로는_로그아웃해도_폐기되지_않는다() {
        ResponseEntity<Map<String, Object>> mine = login(seedAdmin(true), PASSWORD);
        ResponseEntity<Map<String, Object>> others = login(seedAdmin(true), PASSWORD);

        post("/api/admin/auth/logout", "{\"refreshToken\":\"" + refreshToken(others) + "\"}",
                bearer(accessToken(mine)));

        assertThat(refresh(refreshToken(others)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void 로그아웃은_로그인이_필요하다() {
        ResponseEntity<Map<String, Object>> response = post("/api/admin/auth/logout", "", new HttpHeaders());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 로그인_후_계정이_비활성화되면_me는_401이다() {
        String username = seedAdmin(true);
        String accessToken = accessToken(login(username, PASSWORD));
        jdbcTemplate.update("UPDATE admin_users SET is_active = false WHERE username = ?", username);

        ResponseEntity<Map<String, Object>> response = get("/api/admin/auth/me", accessToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code")).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void 허용된_Origin에는_CORS_헤더를_붙이고_쿠키_자격_증명은_허용하지_않는다() {
        String username = seedAdmin(true);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.ORIGIN, ALLOWED_ORIGIN);

        ResponseEntity<Map<String, Object>> response = login(username, PASSWORD, headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo(ALLOWED_ORIGIN);
        assertThat(response.getHeaders().getAccessControlAllowCredentials()).isFalse();
    }

    @Test
    void 허용되지_않은_Origin의_요청은_CORS가_거부한다() {
        String username = seedAdmin(true);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.ORIGIN, "https://evil.example.com");
        headers.setContentType(MediaType.APPLICATION_JSON);

        // CORS 거부 응답은 ErrorResponse(JSON)가 아니라 CorsFilter가 바로 쓴 본문이라 문자열로 받는다.
        ResponseEntity<String> response = restTemplate.exchange("/api/admin/auth/login", HttpMethod.POST,
                new HttpEntity<>("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}", headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
    }
}
