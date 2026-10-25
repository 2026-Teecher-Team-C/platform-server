package com.teecherteamc.platform.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.teecherteamc.platform.admin.domain.AdminRole;
import com.teecherteamc.platform.admin.domain.AdminUser;
import com.teecherteamc.platform.admin.domain.AdminUserRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** AUTH_ENABLED=false인데 행위자로 쓸 초기 관리자가 없으면 null 행위자로 돌지 않고 기동을 멈춘다. */
class AuthDisabledAdminTest {

    private final InMemoryAdminUserRepository repository = new InMemoryAdminUserRepository();

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(AdminUserRepository.class, () -> repository)
            .withUserConfiguration(AuthDisabledAdmin.class);

    @Test
    void 인증을_끄고_초기_관리자_계정명이_없으면_기동에_실패한다() {
        contextRunner.withPropertyValues("platform.auth.enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("ADMIN_BOOTSTRAP_USERNAME");
                });
    }

    @Test
    void 인증이_켜져_있으면_만들지_않는다() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(AuthDisabledAdmin.class);
        });
    }

    @Test
    void 계정명은_있지만_그_계정이_없으면_기동_검사에서_실패한다() {
        AuthDisabledAdmin admin = new AuthDisabledAdmin(repository, "missing-admin");

        assertThatThrownBy(() -> admin.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing-admin");
    }

    @Test
    void 비활성_계정도_행위자로_쓰지_않는다() {
        AdminUser user = AdminUser.create("inactive-admin", "hash", AdminRole.ADMIN, Instant.now());
        user.deactivate(Instant.now());
        repository.save(user);
        AuthDisabledAdmin admin = new AuthDisabledAdmin(repository, "inactive-admin");

        assertThatThrownBy(() -> admin.run(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 초기_관리자를_행위자로_돌려준다() {
        AdminUser user = AdminUser.create("dev-admin", "hash", AdminRole.ADMIN, Instant.now());
        repository.save(user);
        AuthDisabledAdmin admin = new AuthDisabledAdmin(repository, "dev-admin");

        admin.run(null);

        assertThat(admin.require().adminId()).isEqualTo(user.getAdminId());
        assertThat(admin.require().username()).isEqualTo("dev-admin");
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
