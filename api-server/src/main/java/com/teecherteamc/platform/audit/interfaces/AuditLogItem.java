package com.teecherteamc.platform.audit.interfaces;

import com.teecherteamc.platform.audit.domain.AuditAction;
import com.teecherteamc.platform.audit.domain.AuditLogRow;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 감사 로그 한 건의 응답. 콘솔 console/src/lib/api/auditLogs.ts의 AuditLogItem과 필드 이름이 같다.
 *
 * @param dangerous 컬럼이 아니라 계산값이다 (AuditAction.isDangerous)
 * @param actor     행위자가 확정되지 않은 행위(LOGIN_FAILED 등)는 null
 * @param detail    저장된 JSON 문자열을 객체로 풀어 내린다. JSON이 아니면 문자열 그대로, 없으면 null
 */
public record AuditLogItem(
        long logId,
        String action,
        boolean dangerous,
        Actor actor,
        String targetType,
        String targetId,
        Object detail,
        String ipAddress,
        Instant createdAt
) {

    /** 행위자 요약. 콘솔의 AuditActor({ adminId, username, role })와 같다. */
    public record Actor(UUID adminId, String username, String role) {
    }

    public static AuditLogItem from(AuditLogRow row, JsonMapper jsonMapper) {
        Actor actor = row.actorId() == null ? null : new Actor(row.actorId(), row.actorUsername(), row.actorRole());
        return new AuditLogItem(
                row.logId(),
                row.action(),
                AuditAction.isDangerous(row.action()),
                actor,
                row.targetType(),
                row.targetId(),
                parseDetail(row.detail(), jsonMapper),
                row.ipAddress(),
                row.createdAt());
    }

    private static Object parseDetail(String detail, JsonMapper jsonMapper) {
        if (detail == null) {
            return null;
        }
        try {
            return jsonMapper.readTree(detail);
        } catch (JacksonException e) {
            // 기록기는 항상 JSON을 쓰지만, 손으로 넣은 행 때문에 목록 전체가 깨지지 않게 문자열로 둔다.
            return detail;
        }
    }
}
