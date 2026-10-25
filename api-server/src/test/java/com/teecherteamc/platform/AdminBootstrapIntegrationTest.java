package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.teecherteamc.platform.admin.application.AdminBootstrap;
import com.teecherteamc.platform.admin.domain.AdminUserRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
class AdminBootstrapIntegrationTest {

    @Autowired
    private AdminUserRepository adminUserRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private AdminBootstrap bootstrap(String username, String password) {
        return new AdminBootstrap(adminUserRepository, passwordEncoder, username, password);
    }

    private List<Map<String, Object>> rowsFor(String username) {
        return jdbcTemplate.queryForList(
                "SELECT password_hash, role, is_active FROM admin_users WHERE username = ?", username);
    }

    @Test
    void 계정명과_비밀번호가_있고_계정이_없으면_ADMIN으로_만든다() throws Exception {
        String username = "boot-" + UUID.randomUUID();

        bootstrap(username, "initial-password-1").run(null);

        List<Map<String, Object>> rows = rowsFor(username);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("role")).isEqualTo("ADMIN");
        assertThat(rows.get(0).get("is_active")).isEqualTo(true);
        String hash = (String) rows.get(0).get("password_hash");
        assertThat(hash).startsWith("$2").doesNotContain("initial-password-1");
        assertThat(passwordEncoder.matches("initial-password-1", hash)).isTrue();
    }

    @Test
    void 같은_계정명이_이미_있으면_비밀번호를_바꾸지_않는다() throws Exception {
        String username = "boot-" + UUID.randomUUID();
        bootstrap(username, "first-password").run(null);
        String before = (String) rowsFor(username).get(0).get("password_hash");

        bootstrap(username, "second-password").run(null);

        List<Map<String, Object>> rows = rowsFor(username);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("password_hash")).isEqualTo(before);
    }

    @Test
    void 비밀번호가_비어_있으면_랜덤_비밀번호로_ADMIN을_만든다() throws Exception {
        String first = "boot-" + UUID.randomUUID();
        String second = "boot-" + UUID.randomUUID();

        bootstrap(first, "").run(null);
        bootstrap(second, null).run(null);

        List<Map<String, Object>> firstRows = rowsFor(first);
        List<Map<String, Object>> secondRows = rowsFor(second);
        assertThat(firstRows).hasSize(1);
        assertThat(secondRows).hasSize(1);
        assertThat(firstRows.get(0).get("role")).isEqualTo("ADMIN");
        assertThat((String) firstRows.get(0).get("password_hash")).startsWith("$2");
        assertThat(firstRows.get(0).get("password_hash")).isNotEqualTo(secondRows.get(0).get("password_hash"));
    }

    @Test
    void 계정명이_비어_있으면_아무것도_만들지_않는다() throws Exception {
        Integer before = jdbcTemplate.queryForObject("SELECT count(*) FROM admin_users", Integer.class);

        bootstrap("", "").run(null);
        bootstrap("", "password-only").run(null);
        bootstrap(null, null).run(null);

        Integer after = jdbcTemplate.queryForObject("SELECT count(*) FROM admin_users", Integer.class);
        assertThat(after).isEqualTo(before);
    }
}
