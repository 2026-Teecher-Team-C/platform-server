package com.teecherteamc.platform.dashboard.domain;

import java.time.Instant;

/**
 * 대시보드 상단 카운터. 콘솔 DashboardSummary 타입과 같은 모양이다.
 *
 * <ul>
 *   <li>차단 = BLOCKED·FAIL_CLOSE, 악성 = BLOCKED 중 출처 ENGINE·CACHE·BLACKLIST(TLSH 유사도 차단은 빠진다)</li>
 *   <li>검사 = 판정이 있고 BYPASSED가 아닌 이벤트, 차단율 = 차단 / 검사(검사가 0이면 0)</li>
 *   <li>blockedPreviousPeriod = 바로 앞 같은 길이 기간의 차단 수</li>
 * </ul>
 */
public record DashboardSummary(
        Period period,
        long blocked,
        long blockedPreviousPeriod,
        Malicious malicious,
        long failClose,
        long affectedAgents,
        long totalAgents,
        long inspected,
        double blockRate) {

    public record Period(Instant from, Instant to) {
    }

    public record Malicious(long total, long engine, long cache, long blacklist) {
    }

    public static DashboardSummary of(DashboardPeriod period, BlockCounts current, long blockedPreviousPeriod,
                                      long totalAgents) {
        long malicious = current.engine() + current.cache() + current.blacklist();
        double blockRate = current.inspected() == 0 ? 0 : (double) current.blocked() / current.inspected();
        return new DashboardSummary(
                new Period(period.from(), period.to()),
                current.blocked(),
                blockedPreviousPeriod,
                new Malicious(malicious, current.engine(), current.cache(), current.blacklist()),
                current.failClose(),
                current.affectedAgents(),
                totalAgents,
                current.inspected(),
                blockRate);
    }
}
