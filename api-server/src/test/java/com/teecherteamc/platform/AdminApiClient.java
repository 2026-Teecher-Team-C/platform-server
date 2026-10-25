package com.teecherteamc.platform;

import java.util.Map;
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
 * 로그인이 필요한 관리 API 테스트용 클라이언트. 처음 요청할 때 관리자 계정을 하나 만들어 로그인하고, 받은 access 토큰을
 * {@code Authorization: Bearer}로 붙여 요청한다. 테스트 클래스에 {@code @Import(AdminApiClient.class)}로 넣어 쓴다.
 */
@TestComponent
public class AdminApiClient {

    private static final String PASSWORD = "admin-api-client-password";

    private final TestRestTemplate restTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    private String accessToken;
    private UUID adminId;
    private String username;

    public AdminApiClient(TestRestTemplate restTemplate, JdbcTemplate jdbcTemplate,
                          PasswordEncoder passwordEncoder) {
        this.restTemplate = restTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    public <T> ResponseEntity<T> getForEntity(String url, Class<T> responseType) {
        return exchange(url, HttpMethod.GET, null, responseType);
    }

    /** body는 JSON으로 보낸다. 본문이 없으면 null. */
    public <T> ResponseEntity<T> exchange(String url, HttpMethod method, Object body, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken());
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return restTemplate.exchange(url, method, new HttpEntity<>(body, headers), responseType);
    }

    /** 이 클라이언트가 로그인한 관리자의 admin_id. 필요하면 먼저 로그인한다. */
    public UUID adminId() {
        accessToken();
        return adminId;
    }

    public String username() {
        accessToken();
        return username;
    }

    private String accessToken() {
        if (accessToken == null) {
            accessToken = login();
        }
        return accessToken;
    }

    @SuppressWarnings("unchecked")
    private String login() {
        username = "test-admin-" + UUID.randomUUID();
        adminId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO admin_users (admin_id, username, password_hash, role, is_active)
                VALUES (?, ?, ?, 'ADMIN', true)
                """,
                adminId, username, passwordEncoder.encode(PASSWORD));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}";
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/admin/auth/login", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);

        Object token = response.getBody() == null ? null : response.getBody().get("accessToken");
        if (!(token instanceof String value)) {
            throw new AssertionError("로그인 실패: " + response.getStatusCode() + " " + response.getBody());
        }
        return value;
    }
}
