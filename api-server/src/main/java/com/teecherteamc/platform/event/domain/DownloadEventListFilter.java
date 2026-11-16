package com.teecherteamc.platform.event.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 다운로드 이벤트 목록 조건. null이거나 빈 값은 조건 없음이다.
 *
 * @param decisions       판정 중 하나와 같으면 통과
 * @param minSeverityRank 차단(BLOCKED) 이벤트의 심각도 순위(LOW=1 … CRITICAL=4)가 이 값 이상
 * @param acknowledged    확인 여부. 차단(BLOCKED·FAIL_CLOSE)이 아니면 언제나 확인 안 함이다
 */
public record DownloadEventListFilter(
        UUID agentId,
        List<String> decisions,
        Instant from,
        Instant to,
        Integer minSeverityRank,
        Boolean acknowledged) {

    public DownloadEventListFilter {
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
    }
}
