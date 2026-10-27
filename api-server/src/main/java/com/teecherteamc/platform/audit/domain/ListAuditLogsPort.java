package com.teecherteamc.platform.audit.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 감사 로그 목록 조회 포트. 행위자 username·role을 같이 내려야 해서 애그리거트 대신 조인 쿼리로 읽는다.
 */
public interface ListAuditLogsPort {

    /**
     * 모든 값은 null이면 거르지 않는다.
     *
     * @param from          이 시각 이후(포함)
     * @param to            이 시각 이전(제외)
     * @param dangerousOnly true면 AuditAction.dangerousNames()의 action만 남긴다
     */
    record Filter(UUID actorId, String action, String targetType, Instant from, Instant to, boolean dangerousOnly) {
    }

    List<AuditLogRow> list(Filter filter, long offset, int limit);

    long count(Filter filter);
}
