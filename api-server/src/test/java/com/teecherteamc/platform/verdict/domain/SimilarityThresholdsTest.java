package com.teecherteamc.platform.verdict.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

// 허용 범위를 일부러 숫자 그대로 적었다. SimilarityThresholds의 상수를 바꾸면 이 테스트가 깨져서
// 범위를 바꾸는 결정이 눈에 띄게 한다. 경고 상한 70은 L-value 인덱스 창(±5) 때문이다
// (SimilarityThresholdWindowGuardTest가 둘이 어긋나지 않는지 따로 막는다).
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
        // 차단 최대 50이어도 경고는 51~70이 남는다. 경고 최대 70.
        assertThat(new SimilarityThresholds(50, 51)).isNotNull();
        assertThat(new SimilarityThresholds(50, 70)).isNotNull();
        assertThat(new SimilarityThresholds(0, 70)).isNotNull();
    }

    @Test
    void 차단_임계값이_범위를_벗어나면_거부한다() {
        assertThatThrownBy(() -> new SimilarityThresholds(-1, 70))
                .isInstanceOf(InvalidSimilarityThresholdException.class)
                .hasMessageContaining("차단 임계값");
        assertThatThrownBy(() -> new SimilarityThresholds(51, 70))
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
    void 경고_임계값이_상한_70을_넘으면_거부한다() {
        // 71은 L-value 인덱스가 걸러내는 후보에 경고 대상이 섞일 수 있는 첫 값이다.
        assertThatThrownBy(() -> new SimilarityThresholds(30, 71))
                .isInstanceOf(InvalidSimilarityThresholdException.class)
                .hasMessageContaining("경고 임계값");
        assertThatThrownBy(() -> new SimilarityThresholds(50, 100))
                .isInstanceOf(InvalidSimilarityThresholdException.class)
                .hasMessageContaining("경고 임계값");
    }
}