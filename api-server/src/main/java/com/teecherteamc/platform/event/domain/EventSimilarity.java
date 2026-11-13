package com.teecherteamc.platform.event.domain;

import java.time.Instant;

/**
 * 다운로드 당시 연결된 평가만 공개한다. 바깥 similarity의 null은 기록 없음이며,
 * NONE(평가 완료, 경고 범위 일치 없음), UNAVAILABLE(계산 불가)와 다르다.
 * 샘플은 당시 스냅샷이다. 내부 corpus 잠금/토큰과 TLSH 원문은 관리 화면에 노출하지 않는다.
 */
public record EventSimilarity(
        String classification,
        String matchedSampleSha256,
        Integer distance,
        Instant assessedAt) {
}
