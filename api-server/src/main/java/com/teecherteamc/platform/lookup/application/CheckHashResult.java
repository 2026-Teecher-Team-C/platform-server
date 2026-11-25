package com.teecherteamc.platform.lookup.application;

import com.teecherteamc.platform.verdict.domain.VerdictEvidence;

/** CheckHash 판정. evidence는 차단(BLOCK)일 때만 의미가 있고, 그 밖에는 {@link VerdictEvidence#none()}이다. */
public record CheckHashResult(String decision, String source, String reason, VerdictEvidence evidence) {

    public CheckHashResult(String decision, String source, String reason) {
        this(decision, source, reason, VerdictEvidence.none());
    }
}
