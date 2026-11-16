package com.teecherteamc.platform.dashboard.domain;

/** 기간 안 이벤트 건수. 정의는 {@link DashboardSummary} 참고. */
public record BlockCounts(
        long blocked,
        long engine,
        long cache,
        long blacklist,
        long failClose,
        long affectedAgents,
        long inspected) {
}
