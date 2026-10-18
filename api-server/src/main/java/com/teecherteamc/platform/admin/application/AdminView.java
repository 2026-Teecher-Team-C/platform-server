package com.teecherteamc.platform.admin.application;

import com.teecherteamc.platform.admin.domain.AdminRole;
import com.teecherteamc.platform.admin.domain.AdminUser;
import java.time.Instant;
import java.util.UUID;

/** 바깥으로 내보내는 관리자 정보. password_hash는 담지 않는다. */
public record AdminView(UUID adminId, String username, AdminRole role, boolean isActive, Instant lastLoginAt) {

    static AdminView from(AdminUser adminUser) {
        return new AdminView(adminUser.getAdminId(), adminUser.getUsername(), adminUser.getRole(),
                adminUser.isActive(), adminUser.getLastLoginAt());
    }
}
