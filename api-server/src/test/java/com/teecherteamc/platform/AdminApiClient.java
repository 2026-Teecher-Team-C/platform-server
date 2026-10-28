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
    private UUID adminId;      // 로그인에 쓴 테스트 관리자의 admin_id
    private String username;   // 그 관리자의 username

    public AdminApiClient(TestRestTemplate restTemplate, JdbcTemplate jdbcTemplate,
                          PasswordEncoder passwordEncoder) {
        this.restTemplate = restTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    public <T> ResponseEntity<T> getForEntity(String url, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken());
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), responseType);
    }

    /** JSON 본문을 담아 POST로 보낸다. body에는 Map이나 요청 DTO를 그대로 넘기면 된다. */
    public <T> ResponseEntity<T> postForEntity(String url, Object body, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), responseType);
    }

    /** JSON 본문을 담아 PATCH로 보낸다. postForEntity와 같고 HTTP 메서드만 다르다. */
    public <T> ResponseEntity<T> patchForEntity(String url, Object body, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(url, HttpMethod.PATCH, new HttpEntity<>(body, headers), responseType);
    }

    /** 본문 없이 DELETE로 보낸다. 로그인한 관리자의 Bearer 토큰을 붙인다. */
    public <T> ResponseEntity<T> deleteForEntity(String url, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken());
        return restTemplate.exchange(url, HttpMethod.DELETE, new HttpEntity<>(headers), responseType);
    }

    /** DELETE·PATCH처럼 메서드를 골라 보낸다. body가 있으면 JSON으로 보내고, 없으면 null을 넘긴다. */
    public <T> ResponseEntity<T> exchange(String url, HttpMethod method, Object body, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken());
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return restTemplate.exchange(url, method, new HttpEntity<>(body, headers), responseType);
    }

    /** 로그인에 쓴 테스트 관리자의 admin_id. 아직 로그인 전이면 먼저 로그인한다. */
    public UUID adminId() {
        accessToken();
        return adminId;
    }

    /** 로그인에 쓴 테스트 관리자의 username. 아직 로그인 전이면 먼저 로그인한다. */
    public String username() {
        accessToken();
        return username;
    }

    /** 로그인한 관리자의 access 토큰. multipart처럼 직접 요청을 만들어야 할 때 쓴다. */
    public String accessToken() {
        if (accessToken == null) {
            accessToken = login();
        }
        return accessToken;
    }

    @SuppressWarnings("unchecked")
    private String login() {
        String loginName = "test-admin-" + UUID.randomUUID();
        UUID newAdminId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO admin_users (admin_id, username, password_hash, role, is_active)
                VALUES (?, ?, ?, 'ADMIN', true)
                """,
                newAdminId, loginName, passwordEncoder.encode(PASSWORD));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"username\":\"" + loginName + "\",\"password\":\"" + PASSWORD + "\"}";
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/admin/auth/login", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);

        Object token = response.getBody() == null ? null : response.getBody().get("accessToken");
        if (!(token instanceof String value)) {
            throw new AssertionError("로그인 실패: " + response.getStatusCode() + " " + response.getBody());
        }
        this.adminId = newAdminId;
        this.username = loginName;
        return value;
    }
}