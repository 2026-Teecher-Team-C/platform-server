package com.teecherteamc.platform.verdict.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.teecherteamc.platform.verdict.domain.SimilarityThresholds;
import org.junit.jupiter.api.Test;

// L-value 인덱스(V19)는 입력 길이 구간 ±WINDOW 밖 후보를 비교에서 뺀다. 빼도 결과가 같으려면
// 창 밖 후보의 TLSH 거리가 항상 경고 상한을 넘어야 한다.
// 누가 경고 상한(SimilarityThresholds.WARN_MAX)을 올리거나 창(TlshLValue.WINDOW)을 줄이면,
// 인덱스가 걸러낸 후보 중에 경고 대상이 생겨 판정이 조용히 달라진다. 그 순간 이 테스트가 깨진다.
class SimilarityThresholdWindowGuardTest {

    // V19 주석: TLSH 길이 페널티는 원형 L 차이가 2 이상이면 차이×12.
    private static final int LENGTH_PENALTY_PER_STEP = 12;

    @Test
    void 창_밖_후보는_거리가_항상_경고_상한을_넘는다() {
        int firstOutsideStep = TlshLValue.WINDOW + 1;

        // 페널티가 "차이×12"로 적용되는 구간(차이 2 이상)에서만 아래 계산이 성립한다.
        assertThat(firstOutsideStep).as("창 밖 첫 칸은 차이 2 이상이어야 한다").isGreaterThanOrEqualTo(2);

        int minDistanceOutsideWindow = firstOutsideStep * LENGTH_PENALTY_PER_STEP;
        assertThat(minDistanceOutsideWindow)
                .as("창 밖 후보의 최소 거리(%d)가 경고 상한(%d)을 넘어야 한다",
                        minDistanceOutsideWindow, SimilarityThresholds.WARN_MAX)
                .isGreaterThan(SimilarityThresholds.WARN_MAX);
    }
}