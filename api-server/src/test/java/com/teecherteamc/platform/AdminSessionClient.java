package com.teecherteamc.platform;

import java.util.List;
import java.util.UUID;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 로그인이 필요한 관리 API 테스트용 클라이언트. 처음 요청할 때 관리자 계정을 하나 만들어 로그인하고, 그 세션 쿠키를 붙여
 * 요청한다. 테스트 클래스에 {@code @Import(AdminSessionClient.class)}로 넣어 쓴다.
 */
@TestComponent
public class AdminSessionClient {

    private static final String PASSWORD = "admin-session-client-password";

    private final TestRestTemplate restTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    private String sessionCookie;

    public AdminSessionClient(TestRestTemplate restTemplate, JdbcTemplate jdbcTemplate,
                              PasswordEncoder passwordEncoder) {
        this.restTemplate = restTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    public <T> ResponseEntity<T> getForEntity(String url, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, sessionCookie());
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), responseType);
    }

    private String sessionCookie() {
        if (sessionCookie == null) {
            sessionCookie = login();
        }
        return sessionCookie;
    }

    private String login() {
        String username = "test-admin-" + UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO admin_users (admin_id, username, password_hash, role, is_active)
                VALUES (?, ?, ?, 'ADMIN', true)
                """,
                UUID.randomUUID(), username, passwordEncoder.encode(PASSWORD));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}";
        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/admin/auth/login", HttpMethod.POST, new HttpEntity<>(body, headers), Void.class);

        List<String> cookies = response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE);
        return cookies.stream()
                .filter(c -> c.startsWith("JSESSIONID="))
                .map(c -> c.split(";", 2)[0])
                .findFirst()
                .orElseThrow(() -> new AssertionError("로그인 실패: " + response.getStatusCode() + " " + cookies));
    }
}
