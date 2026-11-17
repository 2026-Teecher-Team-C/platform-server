package com.teecherteamc.platform.dashboard.domain;

/**
 * 대시보드 상단 카운터. 콘솔 DashboardSummary 타입(console/src/lib/api/dashboard.ts)과 같은 모양이고,
 * 계산 기준은 콘솔 mock(computeSummary)과 같다.
 *
 * @param blockedPreviousPeriod 바로 앞 같은 길이 기간 [from - (to - from), from)의 차단 수
 * @param totalAgents           기간과 무관하게 REVOKED가 아닌 에이전트 수
 * @param blockRate             blocked / inspected. 검사가 0건이면 0
 */
public record DashboardSummary(
        DashboardPeriod period,
        long blocked,
        long blockedPreviousPeriod,
        Malicious malicious,
        long failClose,
        long affectedAgents,
        long totalAgents,
        long inspected,
        double blockRate) {

    /** BLOCKED 중 룰·판정 이력·블랙리스트로 막힌 것. TLSH 유사도(SIMILARITY) 차단은 total에 넣지 않는다. */
    public record Malicious(long total, long engine, long cache, long blacklist) {
    }

    public static DashboardSummary of(DashboardPeriod period, SummaryCounts counts, long totalAgents) {
        Malicious malicious = new Malicious(counts.engine() + counts.cache() + counts.blacklist(),
                counts.engine(), counts.cache(), counts.blacklist());
        double blockRate = counts.inspected() == 0 ? 0 : (double) counts.blocked() / counts.inspected();
        return new DashboardSummary(period, counts.blocked(), counts.blockedPreviousPeriod(), malicious,
                counts.failClose(), counts.affectedAgents(), totalAgents, counts.inspected(), blockRate);
    }
}
