package com.teecherteamc.platform.dashboard.domain;

import java.time.Instant;

/**
 * 엔진 상태 판단용 analyses·download_events 조회. now 이후 기록은 보지 않는다.
 * 실패 = TIMEOUT·CRASH·OOM. UNSUPPORTED는 어느 쪽에도 넣지 않는다.
 */
public interface EngineStatusPort {

    /** now 이하 마지막 SUCCESS와 마지막 실패 시각. 없으면 null. */
    EngineActivity latestActivity(Instant now);

    /** after(배타, null이면 처음부터) 뒤 now 이하의 첫 실패 시각. 없으면 null. */
    Instant firstFailureAfter(Instant after, Instant now);

    /** [since, now] FAIL_CLOSE 수와 겪은 에이전트 수. 이벤트 목록과 같은 agents/devices 조인을 거친다. */
    FailCloseImpact failClosesBetween(Instant since, Instant now);

    record EngineActivity(Instant lastSuccessAt, Instant lastFailureAt) {
    }

    record FailCloseImpact(long failCloses, long affectedAgents) {
    }
}
