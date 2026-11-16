package com.teecherteamc.platform.dashboard.domain;

import java.time.Instant;

/**
 * 위협별 현황 1줄. 룰 하나(kind=RULE) 또는 블랙리스트 전체(kind=BLACKLIST, ruleName=null).
 *
 * @param severity 이 줄에 들어간 차단 중 가장 높은 심각도
 */
public record ThreatRow(
        String kind,
        String ruleName,
        String severity,
        long affectedAgents,
        long blockCount,
        Instant firstSeenAt,
        Instant lastSeenAt) {
}
