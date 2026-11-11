package com.teecherteamc.platform.dashboard.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 확인 안 한 높은 심각도 차단 1행. 읽기 모델이다.
 *
 * @param reasonType     RULE(엔진·판정 이력 차단) 또는 BLACKLIST
 * @param topRuleName    대표 룰. BLACKLIST면 null
 * @param extraRuleCount 대표 룰 외에 걸린 룰 수. BLACKLIST면 0
 */
public record UnacknowledgedBlockRow(
        UUID eventId,
        Instant createdAt,
        UUID agentId,
        String hostname,
        String filename,
        String reasonType,
        String topRuleName,
        int extraRuleCount,
        String severity) {
}
