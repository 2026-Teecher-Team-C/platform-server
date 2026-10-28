package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * AUTH_ENABLED=false(로컬 개발 전용) 모드. 모든 요청이 초기 관리자(ADMIN_BOOTSTRAP_USERNAME)로 처리된다.
 * 다른 통합 테스트는 기본값(인증 켬)으로 돈다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "platform.auth.enabled=false",
                "platform.admin.bootstrap.username=" + AuthDisabledRestIntegrationTest.BOOTSTRAP_USERNAME,
                "platform.admin.bootstrap.password=dev-password"})
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
class AuthDisabledRestIntegrationTest {

    static final String BOOTSTRAP_USERNAME = "dev-admin";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID bootstrapAdminId() {
        return jdbcTemplate.queryForObject(
                "SELECT admin_id FROM admin_users WHERE username = ?", UUID.class, BOOTSTRAP_USERNAME);
    }

    @SuppressWarnings("unchecked")
    private static Class<Map<String, Object>> mapType() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }

    @Test
    void 로그인하지_않아도_보호된_경로에_들어간다() {
                 ResponseEntity<Map<String, Object>> response = restTemplate.getForEntity("/api/admin/no-such-path", mapType());
        // 인증을 켜면 401. 꺼져 있으면 인증을 통과해 없는 경로라 404다.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void me는_초기_관리자를_돌려준다() {
        ResponseEntity<Map<String, Object>> response = restTemplate.getForEntity("/api/admin/auth/me", mapType());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("adminId", bootstrapAdminId().toString())
                .containsEntry("username", BOOTSTRAP_USERNAME)
                .containsEntry("role", "ADMIN");
    }

    @Test
    void 감사_로그를_남기는_쓰기는_초기_관리자를_행위자로_기록한다() {
        UUID adminId = bootstrapAdminId();
        Integer before = countLogouts(adminId);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/admin/auth/logout", HttpMethod.POST, HttpEntity.EMPTY, mapType());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(countLogouts(adminId)).isEqualTo(before + 1);
    }

    @Test
    void access_토큰을_실으면_초기_관리자가_아니라_그_관리자로_처리한다() {
        String username = "other-" + UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO admin_users (admin_id, username, password_hash, role, is_active)
                VALUES (?, ?, ?, 'VIEWER', true)
                """,
                UUID.randomUUID(), username, passwordEncoder.encode("other-password"));
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map<String, Object>> login = restTemplate.exchange("/api/admin/auth/login", HttpMethod.POST,
                new HttpEntity<>("{\"username\":\"" + username + "\",\"password\":\"other-password\"}",
                        loginHeaders),
                mapType());
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth((String) login.getBody().get("accessToken"));

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/admin/auth/me", HttpMethod.GET, new HttpEntity<>(headers), mapType());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("username", username);
    }

    @Test
    void 허용되지_않은_Origin은_CORS가_여전히_거부한다() {
        // 인증 스위치는 CORS를 끄지 않는다. 다른 사이트가 개발 중인 로컬 API를 읽지 못하게 하는 것은 그대로다.
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.ORIGIN, "https://evil.example.com");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/admin/auth/logout", HttpMethod.POST, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    private Integer countLogouts(UUID adminId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'LOGOUT' AND actor_id = ? AND target_id = ?",
                Integer.class, adminId, adminId.toString());
    }
}
