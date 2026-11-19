package com.teecherteamc.platform.verdict.interfaces;

import com.teecherteamc.platform.verdict.domain.SimilarityDecisionStore.Thresholds;
import com.teecherteamc.platform.verdict.domain.SimilarityThresholds;

/**
 * GET/PUT /api/admin/similarity-policy 응답. 지금 적용 중인 값과 서버가 허용하는 범위를 함께 준다.
 *
 * 예: {"blockDistance":30,"warnDistance":70,"limits":{"blockMin":0,"blockMax":50,"warnMax":70}}
 */
public record SimilarityThresholdItem(int blockDistance, int warnDistance, Limits limits) {

    /**
     * 콘솔이 입력 검증에 쓰는 허용 범위. 서버의 상수를 그대로 내려서 콘솔이 숫자를 따로 갖지 않게 한다.
     * 경고 임계값의 하한은 차단 값에 따라 달라지므로(차단 + 1) 여기에 넣지 않고 콘솔이 계산한다.
     */
    public record Limits(int blockMin, int blockMax, int warnMax) {
    }

    public static SimilarityThresholdItem from(Thresholds thresholds) {
        return new SimilarityThresholdItem(thresholds.blockDistance(), thresholds.warnDistance(),
                new Limits(SimilarityThresholds.BLOCK_MIN, SimilarityThresholds.BLOCK_MAX,
                        SimilarityThresholds.WARN_MAX));
    }
}