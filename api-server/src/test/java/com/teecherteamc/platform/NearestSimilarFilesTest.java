package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.teecherteamc.platform.verdict.domain.NearestSimilarFiles;
import com.teecherteamc.platform.verdict.domain.SimilarFile;
import com.teecherteamc.platform.verdict.domain.SimilarFiles;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class NearestSimilarFilesTest {

    private static String sha(int n) {
        return String.format("%064x", n);
    }

    @ParameterizedTest
    @CsvSource({"30,29,true", "30,30,true", "30,31,false", "70,70,true", "70,71,false", "0,0,true", "0,1,false"})
    void 임계값_이하만_담고_경계값은_포함한다(int maxDistance, int distance, boolean included) {
        var collector = new NearestSimilarFiles(maxDistance, 10, null);

        collector.offer(sha(1), distance);

        assertThat(collector.files()).hasSize(included ? 1 : 0);
        assertThat(collector.truncated()).isFalse();
    }

    @Test
    void 거리_오름차순이고_같은_거리는_sha_오름차순이다() {
        var collector = new NearestSimilarFiles(70, 10, null);

        collector.offer(sha(3), 20);
        collector.offer(sha(2), 20);
        collector.offer(sha(9), 5);
        collector.offer(sha(1), 70);

        assertThat(collector.files()).containsExactly(
                new SimilarFile(sha(9), 5),
                new SimilarFile(sha(2), 20),
                new SimilarFile(sha(3), 20),
                new SimilarFile(sha(1), 70));
    }

    @Test
    void 정확히_limit개면_잘림이_아니다() {
        var collector = new NearestSimilarFiles(70, 3, null);

        for (int i = 1; i <= 3; i++) {
            collector.offer(sha(i), i);
        }

        assertThat(collector.files()).hasSize(3);
        assertThat(collector.truncated()).isFalse();
    }

    @Test
    void limit보다_하나_많으면_가장_먼_것을_버리고_잘림으로_알린다() {
        var collector = new NearestSimilarFiles(70, 3, null);

        for (int i = 1; i <= 4; i++) {
            collector.offer(sha(i), i * 10);
        }

        assertThat(collector.truncated()).isTrue();
        assertThat(collector.files()).containsExactly(
                new SimilarFile(sha(1), 10), new SimilarFile(sha(2), 20), new SimilarFile(sha(3), 30));
    }

    @Test
    void 아주_많이_들어와도_스캔_순서와_무관하게_가장_가까운_limit개가_남는다() {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            order.add(i);
        }
        Collections.shuffle(order, new Random(7));
        var collector = new NearestSimilarFiles(1000, 5, null);

        order.forEach(i -> collector.offer(sha(i), i % 70));

        // 거리 0(i=0,70,140,...,980)이 15개라 그중 sha가 가장 작은 5개가 남는다.
        assertThat(collector.truncated()).isTrue();
        assertThat(collector.files()).containsExactly(
                new SimilarFile(sha(0), 0), new SimilarFile(sha(70), 0), new SimilarFile(sha(140), 0),
                new SimilarFile(sha(210), 0), new SimilarFile(sha(280), 0));
    }

    @Test
    void 제외한_sha는_거리가_0이어도_담지_않는다() {
        var collector = new NearestSimilarFiles(70, 10, sha(1));

        collector.offer(sha(1), 0);
        collector.offer(sha(2), 0);

        assertThat(collector.files()).containsExactly(new SimilarFile(sha(2), 0));
    }

    @Test
    void 제외할_sha가_없으면_아무것도_제외하지_않는다() {
        var collector = new NearestSimilarFiles(70, 10, null);

        collector.offer(sha(1), 0);

        assertThat(collector.files()).hasSize(1);
    }

    @Test
    void 잘못된_인자는_거부한다() {
        assertThatThrownBy(() -> new NearestSimilarFiles(-1, 1, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NearestSimilarFiles(0, 0, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 결과_목록은_바꿀_수_없고_이후_추가에_영향받지_않는다() {
        var collector = new NearestSimilarFiles(70, 10, null);
        collector.offer(sha(1), 1);
        List<SimilarFile> snapshot = collector.files();

        collector.offer(sha(2), 2);

        assertThat(snapshot).hasSize(1);
        assertThatThrownBy(() -> snapshot.add(new SimilarFile(sha(3), 3)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 지문이_없는_결과는_빈_목록이어도_빈_결과와_상태로_구분된다() {
        var empty = SimilarFiles.ok(sha(1), 30, List.of(), false, 100);
        var noReference = SimilarFiles.noReferenceFingerprint(sha(1), 30);
        var invalid = SimilarFiles.invalidTlsh(30);

        assertThat(empty.status()).isEqualTo(SimilarFiles.Status.OK);
        assertThat(noReference.status()).isEqualTo(SimilarFiles.Status.NO_REFERENCE_FINGERPRINT);
        assertThat(invalid.status()).isEqualTo(SimilarFiles.Status.INVALID_TLSH);
        assertThat(noReference.files()).isEmpty();
        assertThat(invalid.referenceSha256()).isNull();
    }
}
