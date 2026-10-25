package com.teecherteamc.platform.admin.interfaces;

import com.teecherteamc.platform.admin.application.AdminView;
import java.time.Instant;
import java.util.UUID;

/** console/src/lib/api/auth.ts의 CurrentAdmin과 1:1 대응하는 응답 DTO. */
public record CurrentAdminResponse(
        UUID adminId,
        String username,
        String role,
        boolean isActive,
        Instant lastLoginAt) {

    static CurrentAdminResponse from(AdminView admin) {
        return new CurrentAdminResponse(admin.adminId(), admin.username(), admin.role().name(),
                admin.isActive(), admin.lastLoginAt());
    }
}
