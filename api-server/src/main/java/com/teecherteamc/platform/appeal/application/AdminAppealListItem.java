package com.teecherteamc.platform.appeal.application;

import com.teecherteamc.platform.appeal.domain.AppealRow;
import java.time.Instant;
import java.util.UUID;

/** 관리자 소명 목록의 한 줄. reasonPreview는 사유의 앞 80 코드 포인트다. */
public record AdminAppealListItem(
        UUID appealId,
        String status,
        Instant createdAt,
        Instant decidedAt,
        String hostname,
        String filename,
        String sha256,
        String evidenceKind,
        String threat,
        String reasonPreview) {

    static final int PREVIEW_CODE_POINTS = 80;

    static AdminAppealListItem from(AppealRow row) {
        return new AdminAppealListItem(row.appealId(), row.status(), row.createdAt(), row.decidedAt(),
                row.hostname(), row.filename(), row.sha256(), row.evidenceKind(), row.threat(),
                preview(row.reason()));
    }

    private static String preview(String reason) {
        if (reason.codePointCount(0, reason.length()) <= PREVIEW_CODE_POINTS) {
            return reason;
        }
        return reason.substring(0, reason.offsetByCodePoints(0, PREVIEW_CODE_POINTS));
    }
}
