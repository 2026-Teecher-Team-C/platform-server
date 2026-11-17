package com.teecherteamc.platform.dashboard.domain;

/** 상단 카운터용 읽기 전용 집계. ListDownloadEventsPortImpl과 같은 agents/devices 조인을 거친다. */
public interface DashboardSummaryPort {

    /** blockedPreviousPeriod는 바로 앞 같은 길이 기간에서 센다. */
    SummaryCounts counts(DashboardPeriod period);

    /** REVOKED가 아닌 에이전트 수. 기간과 무관하다. */
    long totalAgents();
}
