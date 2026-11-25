package com.teecherteamc.platform.verdict.application;

import com.teecherteamc.platform.verdict.domain.VerdictEvidence;
import java.util.List;

/**
 * decision: ALLOW 또는 BLOCK뿐이다(SubmitFile은 UNKNOWN을 돌려줄 수 없다 — 더 올려볼 데가 없다).
 * evidence는 차단(BLOCK)일 때만 의미가 있고, 그 밖에는 {@link VerdictEvidence#none()}이다.
 */
public record SubmitFileResult(String decision, String source, String reason, List<String> matchedRules,
                               VerdictEvidence evidence) {

    public SubmitFileResult(String decision, String source, String reason, List<String> matchedRules) {
        this(decision, source, reason, matchedRules, VerdictEvidence.none());
    }
}
