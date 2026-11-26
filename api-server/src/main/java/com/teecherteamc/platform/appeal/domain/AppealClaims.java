package com.teecherteamc.platform.appeal.domain;

import com.teecherteamc.platform.verdict.domain.VerdictEvidence;
import java.time.Instant;
import java.util.UUID;

/** 소명 토큰에 서명해 넣는 값. 토큰이 유효하면 이 값만 믿는다(서버에 상태를 두지 않는다). */
public record AppealClaims(
        UUID eventId,
        String sha256,
        UUID agentId,
        VerdictEvidence.Kind kind,
        String threat,
        Instant expiresAt) {
}
