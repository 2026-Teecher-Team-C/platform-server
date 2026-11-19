package com.teecherteamc.platform;

import static com.teecherteamc.platform.TlshFixtures.REFERENCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.teecherteamc.platform.verdict.application.FindSimilarFiles;
import com.teecherteamc.platform.verdict.domain.FingerprintProvenance;
import com.teecherteamc.platform.verdict.domain.SimilarFile;
import com.teecherteamc.platform.verdict.domain.SimilarFiles;
import com.teecherteamc.platform.verdict.domain.SimilarFingerprintSearchPort;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

/** 서비스의 입력 검증과 상태 구분. DB 없이 가짜 포트로 확인한다. */
class FindSimilarFilesTest {

    private static final String SHA = "a".repeat(64);

    private final SimilarFingerprintSearchPort port = mock(SimilarFingerprintSearchPort.class);
    private final FindSimilarFiles service = new FindSimilarFiles(port);

    private static String sha(int n) {
        return String.format("%064x", n);
    }

    /** scan이 호출되면 주어진 (sha, 거리)들을 sink에 흘려 주고 scanned를 돌려준다. */
    private void scanYields(long scanned, Object... shaAndDistance) {
        when(port.scan(any(), any(), anyInt(), any())).thenAnswer(invocation -> {
            BiConsumer<String, Integer> sink = invocation.getArgument(3);
            for (int i = 0; i < shaAndDistance.length; i += 2) {
                sink.accept((String) shaAndDistance[i], (Integer) shaAndDistance[i + 1]);
            }
            return scanned;
        });
    }

    @Test
    void 지문이_없는_sha는_빈_결과가_아니라_NO_REFERENCE_FINGERPRINT이고_스캔하지_않는다() {
        when(port.calculatedTlsh(SHA)).thenReturn(Optional.empty());

        SimilarFiles result = service.bySha256(SHA, 30, 10);

        assertThat(result.status()).isEqualTo(SimilarFiles.Status.NO_REFERENCE_FINGERPRINT);
        assertThat(result.files()).isEmpty();
        verify(port, never()).scan(any(), any(), anyInt(), any());
    }

    @Test
    void sha로_찾으면_기준_자신은_결과에서_빠진다() {
        when(port.calculatedTlsh(SHA)).thenReturn(Optional.of(REFERENCE));
        scanYields(5, SHA, 0, sha(1), 12);

        SimilarFiles result = service.bySha256(SHA, 30, 10);

        assertThat(result.status()).isEqualTo(SimilarFiles.Status.OK);
        assertThat(result.referenceSha256()).isEqualTo(SHA);
        assertThat(result.files()).containsExactly(new SimilarFile(sha(1), 12));
        assertThat(result.scanned()).isEqualTo(5);
    }

    @Test
    void 해석할_수_없는_TLSH는_예외가_아니라_INVALID_TLSH이고_스캔하지_않는다() {
        for (String bad : new String[] {null, "", "not-a-tlsh", "T1" + "Z".repeat(70), "t1" + "A".repeat(70)}) {
            SimilarFiles result = service.byTlsh(bad, 30, 10);

            assertThat(result.status()).as("입력: %s", bad).isEqualTo(SimilarFiles.Status.INVALID_TLSH);
        }
        verifyNoInteractions(port);
    }

    @Test
    void TLSH로_직접_찾으면_제외할_sha를_고를_수_있고_기본_출처는_VERIFIED_UPLOAD다() {
        scanYields(3, sha(1), 0, sha(2), 7);

        SimilarFiles result = service.byTlsh(REFERENCE, 30, 10, FindSimilarFiles.DEFAULT_PROVENANCES, sha(1));

        assertThat(result.referenceSha256()).isNull();
        assertThat(result.files()).containsExactly(new SimilarFile(sha(2), 7));
        verify(port).scan(eq(REFERENCE), eq(Set.of(FingerprintProvenance.VERIFIED_UPLOAD)), eq(30), any());
    }

    @Test
    void limit보다_많으면_가까운_순으로_자르고_truncated로_알린다() {
        scanYields(9, sha(1), 20, sha(2), 10, sha(3), 30);

        SimilarFiles result = service.byTlsh(REFERENCE, 70, 2);

        assertThat(result.truncated()).isTrue();
        assertThat(result.files()).containsExactly(new SimilarFile(sha(2), 10), new SimilarFile(sha(1), 20));
    }

    @Test
    void 범위를_벗어난_인자는_스캔_전에_거부한다() {
        assertThatThrownBy(() -> service.byTlsh(REFERENCE, -1, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.byTlsh(REFERENCE, FindSimilarFiles.MAX_DISTANCE + 1, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.byTlsh(REFERENCE, 30, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.byTlsh(REFERENCE, 30, FindSimilarFiles.HARD_LIMIT + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.byTlsh(REFERENCE, 30, 10, Set.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.bySha256("ABC", 30, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.bySha256(null, 30, 10)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(port);
    }

    @Test
    void 경계값은_허용한다() {
        when(port.scan(any(), any(), anyInt(), any())).thenReturn(0L);

        assertThat(service.byTlsh(REFERENCE, 0, 1).status()).isEqualTo(SimilarFiles.Status.OK);
        assertThat(service.byTlsh(REFERENCE, FindSimilarFiles.MAX_DISTANCE, FindSimilarFiles.HARD_LIMIT).status())
                .isEqualTo(SimilarFiles.Status.OK);
    }
}
