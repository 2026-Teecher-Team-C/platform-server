package com.teecherteamc.platform.admin.application;

import com.teecherteamc.platform.admin.domain.AdminUser;
import com.teecherteamc.platform.admin.domain.AdminUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * AUTH_ENABLED=false(로컬 개발 전용)일 때 모든 요청의 행위자가 되는 초기 관리자.
 *
 * <p>인증을 꺼도 hash_whitelist.added_by·quarantine_files.restored_by 같은 NOT NULL 컬럼과 감사 로그의
 * actor_id가 채워져야 하므로, 로그인하지 않은 요청은 ADMIN_BOOTSTRAP_USERNAME 계정으로 처리한다.
 * 그 계정이 없으면 null 행위자로 돌지 않도록 기동을 멈춘다. 인증이 켜져 있으면(기본값) 이 빈은 없다.
 */
@Component
@ConditionalOnBooleanProperty(name = "platform.auth.enabled", havingValue = false)
@Order(AdminBootstrap.ORDER + 1)
public class AuthDisabledAdmin implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AuthDisabledAdmin.class);

    private final AdminUserRepository adminUserRepository;
    private final String username;
    private volatile AdminView admin;

    public AuthDisabledAdmin(AdminUserRepository adminUserRepository,
                             @Value("${platform.admin.bootstrap.username:}") String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalStateException("AUTH_ENABLED=false면 모든 요청을 초기 관리자로 처리합니다. "
                    + "ADMIN_BOOTSTRAP_USERNAME을 설정하세요.");
        }
        this.adminUserRepository = adminUserRepository;
        this.username = username;
        log.warn("AUTH DISABLED — 관리 API가 로그인 없이 열려 있습니다. 운영에서 쓰지 마세요. "
                + "로그인하지 않은 요청은 초기 관리자 {}로 처리됩니다.", username);
    }

    /** AdminBootstrap이 계정을 만든 뒤에 돈다. 계정이 없거나 비활성이면 기동을 멈춘다. */
    @Override
    public void run(ApplicationArguments args) {
        require();
    }

    public AdminView require() {
        AdminView resolved = admin;
        if (resolved == null) {
            resolved = adminUserRepository.findByUsername(username)
                    .filter(AdminUser::isActive)
                    .map(AdminView::from)
                    .orElseThrow(() -> new IllegalStateException("AUTH_ENABLED=false인데 초기 관리자 계정 "
                            + username + "이 없거나 비활성입니다."));
            admin = resolved;
        }
        return resolved;
    }
}
