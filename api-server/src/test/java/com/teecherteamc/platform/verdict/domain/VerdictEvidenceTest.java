package com.teecherteamc.platform.verdict.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class VerdictEvidenceTest {

    private static final String SHA = "a".repeat(64);

    @Test
    void 패밀리는_출처_접두를_뗀_값이다() {
        assertThat(VerdictEvidence.familyOf("MalwareBazaar: Mirai")).isEqualTo("Mirai");
        assertThat(VerdictEvidence.familyOf("Mirai")).isEqualTo("Mirai");
    }

    @Test
    void 출처_이름뿐이거나_비어_있으면_패밀리는_빈_문자열이다() {
        assertThat(VerdictEvidence.familyOf("MalwareBazaar")).isEmpty();
        assertThat(VerdictEvidence.familyOf("MalwareBazaar: ")).isEmpty();
        assertThat(VerdictEvidence.familyOf(null)).isEmpty();
        assertThat(VerdictEvidence.familyOf("")).isEmpty();
    }

    @Test
    void 블랙리스트_근거는_사유와_패밀리를_담는다() {
        VerdictEvidence evidence = VerdictEvidence.blacklist("MalwareBazaar: Mirai");

        assertThat(evidence.kind()).isEqualTo(VerdictEvidence.Kind.BLACKLIST);
        assertThat(evidence.label()).isEqualTo("MalwareBazaar: Mirai");
        assertThat(evidence.family()).isEqualTo("Mirai");
    }

    @Test
    void 유사_샘플_사유가_없으면_사유와_패밀리는_빈_문자열이다() {
        VerdictEvidence evidence = VerdictEvidence.similarity(12, SHA, null);

        assertThat(evidence.kind()).isEqualTo(VerdictEvidence.Kind.SIMILARITY);
        assertThat(evidence.tlshDistance()).isEqualTo(12);
        assertThat(evidence.matchedSampleSha256()).isEqualTo(SHA);
        assertThat(evidence.label()).isEmpty();
        assertThat(evidence.family()).isEmpty();
    }

    @Test
    void YARA_근거는_룰_순서를_지키고_바꿀_수_없다() {
        List<VerdictEvidence.Rule> rules = List.of(new VerdictEvidence.Rule("A", "HIGH"),
                new VerdictEvidence.Rule("B", "LOW"));

        VerdictEvidence evidence = VerdictEvidence.yara(rules);

        assertThat(evidence.rules()).extracting(VerdictEvidence.Rule::name).containsExactly("A", "B");
        assertThat(evidence.rules()).isUnmodifiable();
    }

    @Test
    void 캐시_근거와_없음() {
        assertThat(VerdictEvidence.cache("MALICIOUS").previousVerdict()).isEqualTo("MALICIOUS");
        assertThat(VerdictEvidence.none().kind()).isEqualTo(VerdictEvidence.Kind.NONE);
        assertThat(VerdictEvidence.none().rules()).isEmpty();
    }
}
