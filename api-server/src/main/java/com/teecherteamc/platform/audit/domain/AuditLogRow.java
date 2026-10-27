package com.teecherteamc.platform.audit.domain;

import java.time.Instant;
import java.util.UUID;

/** 목록 화면용 감사 로그 한 줄. admin_users와 조인해 행위자 username·role까지 담는다. 행위자가 없으면 셋 다 null이다. */
public record AuditLogRow(
        long logId,
        UUID actorId,
        String actorUsername,
        String actorRole,
        String action,
        String targetType,
        String targetId,
        String detail,
        String ipAddress,
        Instant createdAt) {
}
