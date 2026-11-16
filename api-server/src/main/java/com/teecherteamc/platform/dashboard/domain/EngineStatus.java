package com.teecherteamc.platform.dashboard.domain;

import java.time.Instant;

/**
 * 탐지 엔진 상태. 최근 windowMinutes분의 분석 기록(UNSUPPORTED 제외)으로 정한다.
 *
 * <ul>
 *   <li>IDLE: 그 안에 기록이 없다</li>
 *   <li>HEALTHY: 가장 최근 기록이 SUCCESS</li>
 *   <li>DOWN: 가장 최근 기록이 실패(TIMEOUT·CRASH·OOM) = 마지막 실패 뒤 SUCCESS가 없다.
 *       since는 마지막 SUCCESS 뒤 첫 실패, 그 뒤 FAIL_CLOSE 건수와 영향 PC 수를 함께 준다</li>
 * </ul>
 */
public record EngineStatus(
        String state,
        Instant since,
        Instant lastSuccessAt,
        Instant lastFailureAt,
        int windowMinutes,
        long failCloseSinceDown,
        long affectedAgentsSinceDown) {
}
