package com.teecherteamc.platform.dashboard.domain;

/**
 * 기간 안의 이벤트 집계. 차단 = BLOCKED·FAIL_CLOSE, 검사 = decision이 있고 BYPASSED가 아닌 것.
 *
 * @param engine    BLOCKED + decision_source ENGINE (cache·blacklist도 같은 방식)
 * @param affectedAgents 기간 안에 차단이 1건 이상 있는 에이전트 수
 */
public record SummaryCounts(
        long blocked,
        long blockedPreviousPeriod,
        long engine,
        long cache,
        long blacklist,
        long failClose,
        long affectedAgents,
        long inspected) {
}
