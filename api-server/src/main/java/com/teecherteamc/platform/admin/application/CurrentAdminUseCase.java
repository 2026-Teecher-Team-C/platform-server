package com.teecherteamc.platform.admin.application;

import com.teecherteamc.platform.admin.domain.AdminUser;
import com.teecherteamc.platform.admin.domain.AdminUserRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 세션의 관리자를 다시 읽는다. 로그인 뒤에 비활성화됐거나 지워졌으면 빈 값이다. */
@Service
public class CurrentAdminUseCase {

    private final AdminUserRepository adminUserRepository;

    public CurrentAdminUseCase(AdminUserRepository adminUserRepository) {
        this.adminUserRepository = adminUserRepository;
    }

    @Transactional(readOnly = true)
    public Optional<AdminView> findActive(UUID adminId) {
        return adminUserRepository.findById(adminId)
                .filter(AdminUser::isActive)
                .map(AdminView::from);
    }
}
