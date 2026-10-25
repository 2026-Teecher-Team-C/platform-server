package com.teecherteamc.platform.admin.application;

import com.teecherteamc.platform.admin.domain.RefreshTokenStore;
import com.teecherteamc.platform.audit.application.AuditLogRecorder;
import com.teecherteamc.platform.audit.domain.AuditAction;
import com.teecherteamc.platform.audit.domain.AuditTargetType;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그아웃. 받은 refresh 토큰을 폐기하고 LOGOUT을 기록한다. access 토큰은 서버에 상태가 없어 만료될 때까지
 * 유효하다 — 콘솔이 버린다.
 */
@Service
public class AdminLogoutUseCase {

    private final RefreshTokenStore refreshTokenStore;
    private final AuditLogRecorder auditLogRecorder;

    public AdminLogoutUseCase(RefreshTokenStore refreshTokenStore, AuditLogRecorder auditLogRecorder) {
        this.refreshTokenStore = refreshTokenStore;
        this.auditLogRecorder = auditLogRecorder;
    }

    /** @param refreshTokenOrNull 폐기할 refresh 토큰. 없으면 기록만 한다. 남의 토큰이면 폐기하지 않는다 */
    @Transactional
    public void logout(UUID adminId, String refreshTokenOrNull, String ipAddress) {
        if (refreshTokenOrNull != null && !refreshTokenOrNull.isBlank()) {
            refreshTokenStore.revoke(refreshTokenOrNull, adminId);
        }
        auditLogRecorder.record(adminId, AuditAction.LOGOUT, AuditTargetType.ADMIN_USER, adminId.toString(),
                null, ipAddress);
    }
}
