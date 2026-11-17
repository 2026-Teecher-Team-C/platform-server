package com.teecherteamc.platform.dashboard.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * 탐지 엔진 상태 배지. 콘솔 EngineStatus 타입(console/src/lib/api/dashboard.ts)과 같은 모양이고,
 * 판단 기준은 콘솔 mock(computeEngineStatus)과 같다. UNSUPPORTED 분석은 판단에서 뺀다.
 *
 * @param since                   DOWN일 때만: 마지막 SUCCESS 이후 첫 실패 시각
 * @param failCloseSinceDown      DOWN일 때만: since부터 지금까지 FAIL_CLOSE 수. 그 밖은 0
 * @param affectedAgentsSinceDown DOWN일 때만: 그 FAIL_CLOSE를 겪은 에이전트 수. 그 밖은 0
 */
public record EngineStatus(
        EngineState state,
        Instant since,
        Instant lastSuccessAt,
        Instant lastFailureAt,
        long windowMinutes,
        long failCloseSinceDown,
        long affectedAgentsSinceDown) {

    public enum EngineState { HEALTHY, DOWN, IDLE }

    /**
     * 창 안에 기록이 없으면 IDLE, 가장 최근 기록이 실패(마지막 실패 뒤 SUCCESS 없음)면 DOWN, SUCCESS면 HEALTHY.
     * 같은 시각의 SUCCESS와 실패는 SUCCESS로 본다.
     */
    public static EngineState judge(Instant lastSuccessAt, Instant lastFailureAt, Instant now, Duration window) {
        Instant latest = latest(lastSuccessAt, lastFailureAt);
        if (latest == null || latest.isBefore(now.minus(window))) {
            return EngineState.IDLE;
        }
        boolean failedLast = lastFailureAt != null
                && (lastSuccessAt == null || lastFailureAt.isAfter(lastSuccessAt));
        return failedLast ? EngineState.DOWN : EngineState.HEALTHY;
    }

    public static EngineStatus notDown(
            EngineState state, Instant lastSuccessAt, Instant lastFailureAt, Duration window) {
        return new EngineStatus(state, null, lastSuccessAt, lastFailureAt, window.toMinutes(), 0, 0);
    }

    public static EngineStatus down(Instant since, Instant lastSuccessAt, Instant lastFailureAt, Duration window,
            long failCloses, long affectedAgents) {
        return new EngineStatus(EngineState.DOWN, since, lastSuccessAt, lastFailureAt, window.toMinutes(),
                failCloses, affectedAgents);
    }

    private static Instant latest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }
}
