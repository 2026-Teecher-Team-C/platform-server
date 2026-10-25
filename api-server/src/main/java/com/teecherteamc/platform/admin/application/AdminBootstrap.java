package com.teecherteamc.platform.admin.application;

import com.teecherteamc.platform.admin.domain.AdminRole;
import com.teecherteamc.platform.admin.domain.AdminUser;
import com.teecherteamc.platform.admin.domain.AdminUserRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 첫 관리자 계정을 환경 변수(ADMIN_BOOTSTRAP_USERNAME / ADMIN_BOOTSTRAP_PASSWORD)로 만든다.
 *
 * <p>공개 레포라 비밀번호나 해시를 Flyway 마이그레이션에 넣을 수 없어서 기동 시점에 만든다.
 * 둘 다 있고 같은 계정명이 없을 때만 만든다. 이미 있으면 비밀번호를 바꾸지 않는다 — 환경 변수를 바꿔도
 * 기존 계정의 비밀번호는 그대로다. 비밀번호는 로그에 남기지 않는다.
 */
@Component
@Order(AdminBootstrap.ORDER)
public class AdminBootstrap implements ApplicationRunner {

    /** AuthDisabledAdmin이 이 다음에 돌아 계정이 만들어졌는지 확인한다. */
    static final int ORDER = 0;

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AdminUserRepository adminUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;

    public AdminBootstrap(AdminUserRepository adminUserRepository, PasswordEncoder passwordEncoder,
                          @Value("${platform.admin.bootstrap.username:}") String username,
                          @Value("${platform.admin.bootstrap.password:}") String password) {
        this.adminUserRepository = adminUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean hasUsername = username != null && !username.isBlank();
        boolean hasPassword = password != null && !password.isBlank();
        if (!hasUsername && !hasPassword) {
            return;
        }
        if (!hasUsername || !hasPassword) {
            log.warn("ADMIN_BOOTSTRAP_USERNAME과 ADMIN_BOOTSTRAP_PASSWORD 중 하나만 설정돼 있어 초기 관리자를 만들지 않습니다.");
            return;
        }
        if (adminUserRepository.existsByUsername(username)) {
            return;
        }
        adminUserRepository.save(
                AdminUser.create(username, passwordEncoder.encode(password), AdminRole.ADMIN, Instant.now()));
        log.info("초기 관리자 계정 {}을(를) 만들었습니다.", username);
    }
}
