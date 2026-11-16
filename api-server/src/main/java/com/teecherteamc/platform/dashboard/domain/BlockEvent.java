package com.teecherteamc.platform.dashboard.domain;

import java.time.Instant;

/**
 * 기간 안의 차단(BLOCKED·FAIL_CLOSE) 이벤트 1건. 일별 차단 집계에 쓴다.
 *
 * @param decisionSource FAIL_CLOSE면 null일 수 있다
 * @param topRuleName    BLOCKED이고 룰로 막힌(ENGINE·CACHE) 경우의 대표 룰. 그 밖은 null
 */
public record BlockEvent(Instant createdAt, String decision, String decisionSource, String topRuleName) {

    public boolean failClose() {
        return "FAIL_CLOSE".equals(decision);
    }
}
