package com.teecherteamc.platform.admin.application;

import com.teecherteamc.platform.audit.application.AuditLogRecorder;
import com.teecherteamc.platform.audit.domain.AuditAction;
import com.teecherteamc.platform.audit.domain.AuditTargetType;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 로그아웃 기록. 세션을 끊는 일은 interfaces 계층이 한다. */
@Service
public class AdminLogoutUseCase {

    private final AuditLogRecorder auditLogRecorder;

    public AdminLogoutUseCase(AuditLogRecorder auditLogRecorder) {
        this.auditLogRecorder = auditLogRecorder;
    }

    @Transactional
    public void logout(UUID adminId, String ipAddress) {
        auditLogRecorder.record(adminId, AuditAction.LOGOUT, AuditTargetType.ADMIN_USER, adminId.toString(),
                null, ipAddress);
    }
}
