package com.teecherteamc.platform.appeal.application;

import com.teecherteamc.platform.appeal.domain.AppealRow;
import com.teecherteamc.platform.event.domain.EventSimilarity;
import java.time.Instant;
import java.util.UUID;

/**
 * 관리자 소명 상세. similarity는 차단 당시 이벤트에 연결된 TLSH 평가이며, 기록이 없으면 null이다.
 */
public record AdminAppealDetail(
        UUID appealId,
        String status,
        Instant createdAt,
        Instant decidedAt,
        String hostname,
        String filename,
        String sha256,
        String evidenceKind,
        String threat,
        String reasonPreview,
        UUID eventId,
        UUID agentId,
        String reason,
        String decisionNote,
        String decidedBy,
        boolean blacklisted,
        Instant blockedAt,
        Similarity similarity) {

    public record Similarity(String classification, Integer distance, String matchedSampleSha256) {

        static Similarity from(EventSimilarity source) {
            return source == null ? null
                    : new Similarity(source.classification(), source.distance(), source.matchedSampleSha256());
        }
    }

    static AdminAppealDetail from(AppealRow row, EventSimilarity similarity) {
        AdminAppealListItem item = AdminAppealListItem.from(row);
        return new AdminAppealDetail(row.appealId(), row.status(), row.createdAt(), row.decidedAt(),
                row.hostname(), row.filename(), row.sha256(), row.evidenceKind(), row.threat(),
                item.reasonPreview(), row.eventId(), row.agentId(), row.reason(), row.decisionNote(),
                row.decidedBy(), row.blacklisted(), row.blockedAt(), Similarity.from(similarity));
    }
}
