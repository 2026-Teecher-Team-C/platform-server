package com.teecherteamc.platform.admin.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.teecherteamc.platform.admin.domain.AdminUser;
import com.teecherteamc.platform.admin.domain.AdminUserRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 비밀번호가 비어 있을 때 AdminBootstrap이 만든 원문이 길이·문자·무작위성 조건을 지키는지 암호화기에 넘어간 값으로 확인한다. */
class AdminBootstrapGeneratedPasswordTest {

    private final InMemoryAdminUserRepository repository = new InMemoryAdminUserRepository();
    private final RecordingPasswordEncoder encoder = new RecordingPasswordEncoder();

    @Test
    void 랜덤_비밀번호는_32글자의_URL_안전_문자다() throws Exception {
        new AdminBootstrap(repository, encoder, "admin", "").run(null);

        assertThat(encoder.rawPasswords).hasSize(1);
        assertThat(encoder.rawPasswords.get(0)).hasSize(32).matches("[A-Za-z0-9_-]{32}");
    }

    @Test
    void 랜덤_비밀번호는_계정마다_다르다() throws Exception {
        new AdminBootstrap(repository, encoder, "admin-" + UUID.randomUUID(), null).run(null);
        new AdminBootstrap(repository, encoder, "admin-" + UUID.randomUUID(), null).run(null);

        assertThat(encoder.rawPasswords).hasSize(2).doesNotHaveDuplicates();
    }

    @Test
    void 비밀번호를_직접_주면_그_값을_그대로_쓴다() throws Exception {
        new AdminBootstrap(repository, encoder, "admin", "given-password").run(null);

        assertThat(encoder.rawPasswords).containsExactly("given-password");
    }

    private static final class RecordingPasswordEncoder implements PasswordEncoder {

        private final List<String> rawPasswords = new ArrayList<>();

        @Override
        public String encode(CharSequence rawPassword) {
            rawPasswords.add(rawPassword.toString());
            return "encoded";
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            return false;
        }
    }

    private static final class InMemoryAdminUserRepository implements AdminUserRepository {

        private final Map<String, AdminUser> byUsername = new HashMap<>();

        @Override
        public Optional<AdminUser> findById(UUID adminId) {
            return byUsername.values().stream().filter(u -> u.getAdminId().equals(adminId)).findFirst();
        }

        @Override
        public Optional<AdminUser> findByUsername(String username) {
            return Optional.ofNullable(byUsername.get(username));
        }

        @Override
        public boolean existsByUsername(String username) {
            return byUsername.containsKey(username);
        }

        @Override
        public AdminUser save(AdminUser adminUser) {
            byUsername.put(adminUser.getUsername(), adminUser);
            return adminUser;
        }
    }
}
