package com.teecherteamc.platform.appeal.application;

import com.teecherteamc.platform.appeal.domain.Appeal;
import com.teecherteamc.platform.appeal.domain.AppealClaims;
import com.teecherteamc.platform.appeal.domain.AppealStatus;
import com.teecherteamc.platform.appeal.domain.BlockedDownload;
import com.teecherteamc.platform.verdict.domain.VerdictEvidence;
import java.time.Instant;

/**
 * 소명 화면 한 장에 필요한 값. 공개 응답이므로 URL·호스트·장비 이름·에이전트 ID·샘플 SHA-256은 넣지 않는다.
 * 값이 없으면 null이다. 승인된 소명의 decisionNote는 항상 null이다. eventId는 직원이 차단 페이지에서 이미 본 값이며, 문의할 때 보안 담당자에게 알려 주는 용도다.
 */
public record AppealView(String eventId, Instant blockedAt, String filename, Verdict verdict, Submitted appeal) {

    /** 차단 근거 요약. summary는 에이전트 차단 페이지(block_page._explain)와 같은 문구다. */
    public record Verdict(String kind, String threat, String summary) {
    }

    public record Submitted(String status, String reason, Instant createdAt, Instant decidedAt, String decisionNote) {
    }

    static AppealView of(AppealClaims claims, BlockedDownload blocked, Appeal appeal) {
        return new AppealView(
                claims.eventId().toString(),
                blocked == null ? null : blocked.blockedAt(),
                blocked == null ? null : blocked.filename(),
                verdictOf(claims.kind(), claims.threat()),
                appeal == null ? null : new Submitted(appeal.getStatus().name(), appeal.getReason(),
                        appeal.getCreatedAt(), appeal.getDecidedAt(), publicNote(appeal)));
    }

    /** 승인 의견은 화이트리스트 사유로도 쓰는 내부 메모라 직원에게 보내지 않는다. 거절 의견만 공개한다. */
    private static String publicNote(Appeal appeal) {
        return appeal.getStatus() == AppealStatus.REJECTED ? appeal.getDecisionNote() : null;
    }

    private static Verdict verdictOf(VerdictEvidence.Kind kind, String threat) {
        String summary;
        String defaultThreat;
        switch (kind) {
            case BLACKLIST -> {
                summary = "보안 위협 목록에 등록된 파일과 똑같은 파일입니다.";
                defaultThreat = "알려진 악성 파일";
            }
            case SIMILARITY -> {
                summary = "알려진 악성코드와 매우 비슷한 파일입니다. 일부만 바꾼 변종일 수 있습니다.";
                defaultThreat = "알려진 악성코드 변종";
            }
            case YARA -> {
                summary = "악성코드 탐지 규칙에 걸렸습니다.";
                defaultThreat = "악성코드 탐지 규칙";
            }
            case CACHE -> {
                summary = "이전에 악성으로 판정된 파일입니다.";
                defaultThreat = "이전 검사 결과";
            }
            default -> {
                // 근거 없는 차단에는 소명 링크를 만들지 않는다. 그래도 들어오면 근거를 말하지 않는다.
                return null;
            }
        }
        return new Verdict(kind.name(), threat == null || threat.isEmpty() ? defaultThreat : threat, summary);
    }
}
