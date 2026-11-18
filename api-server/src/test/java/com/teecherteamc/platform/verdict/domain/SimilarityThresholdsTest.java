package com.teecherteamc.platform.verdict.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

// 허용 범위를 일부러 숫자 그대로 적었다. SimilarityThresholds의 상수를 바꾸면 이 테스트가 깨져서
// 범위를 바꾸는 결정이 눈에 띄게 한다.
class SimilarityThresholdsTest {

    @Test
    void 기본값_30과_70은_허용한다() {
        SimilarityThresholds thresholds = new SimilarityThresholds(30, 70);

        assertThat(thresholds.blockDistance()).isEqualTo(30);
        assertThat(thresholds.warnDistance()).isEqualTo(70);
    }

    @Test
    void 경계값은_허용한다() {
        // 차단 최소 0, 경고는 차단보다 1만 커도 된다.
        assertThat(new SimilarityThresholds(0, 1)).isNotNull();
        // 차단 최대 50, 경고 최대 100.
        assertThat(new SimilarityThresholds(50, 100)).isNotNull();
    }

    @Test
    void 차단_임계값이_범위를_벗어나면_거부한다() {
        assertThatThrownBy(() -> new SimilarityThresholds(-1, 70))
                .isInstanceOf(InvalidSimilarityThresholdException.class)
                .hasMessageContaining("차단 임계값");
        assertThatThrownBy(() -> new SimilarityThresholds(51, 80))
                .isInstanceOf(InvalidSimilarityThresholdException.class)
                .hasMessageContaining("차단 임계값");
    }

    @Test
    void 경고_임계값이_차단_이하이면_거부한다() {
        // 같은 값이면 WARN 구간이 한 칸도 없어서 거부한다.
        assertThatThrownBy(() -> new SimilarityThresholds(30, 30))
                .isInstanceOf(InvalidSimilarityThresholdException.class)
                .hasMessageContaining("경고 임계값");
        assertThatThrownBy(() -> new SimilarityThresholds(40, 30))
                .isInstanceOf(InvalidSimilarityThresholdException.class)
                .hasMessageContaining("경고 임계값");
    }

    @Test
    void 경고_임계값이_상한을_넘으면_거부한다() {
        assertThatThrownBy(() -> new SimilarityThresholds(30, 101))
                .isInstanceOf(InvalidSimilarityThresholdException.class)
                .hasMessageContaining("경고 임계값");
    }
}