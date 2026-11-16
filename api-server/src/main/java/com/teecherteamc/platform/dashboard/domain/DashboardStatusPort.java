package com.teecherteamc.platform.dashboard.domain;

import java.time.Instant;
import java.util.Optional;

/** 대시보드 카운터와 엔진 상태용 읽기 전용 조회. 이벤트 집계는 agents/devices 조인을 거친다. */
public interface DashboardStatusPort {

    BlockCounts counts(DashboardPeriod period);

    long blockedCount(DashboardPeriod period);

    /** REVOKED가 아닌 에이전트 수. */
    long activeAgents();

    /** now 이전의 가장 최근 분석(UNSUPPORTED 제외). */
    Optional<AnalysisMark> latestAnalysis(Instant now);

    /** now 이전 가장 최근 SUCCESS 시각. */
    Optional<Instant> lastSuccessAt(Instant now);

    /** now 이전 가장 최근 실패(TIMEOUT·CRASH·OOM) 시각. */
    Optional<Instant> lastFailureAt(Instant now);

    /** after(없으면 처음부터) 뒤, now 이전의 첫 실패 시각. */
    Optional<Instant> firstFailureAfter(Instant after, Instant now);

    /** [since, now] 사이 FAIL_CLOSE 건수와 영향 PC 수. */
    FailCloseImpact failClosesBetween(Instant since, Instant now);

    record AnalysisMark(String status, Instant analyzedAt) {

        public boolean success() {
            return "SUCCESS".equals(status);
        }
    }

    record FailCloseImpact(long events, long agents) {
    }
}
