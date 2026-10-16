package com.teecherteamc.platform.admin.interfaces;

import com.teecherteamc.platform.event.domain.DownloadEventListRow;
import java.time.Instant;
import java.util.UUID;

/** console/src/lib/api/events.ts의 DownloadEventListItem과 1:1 대응하는 응답 DTO. */
public record DownloadEventListItem(
        UUID eventId,
        UUID agentId,
        String hostname,
        String sha256,
        String filename,
        String mimeType,
        Long fileSize,
        String decision,
        String decisionSource,
        boolean cacheHit,
        Integer holdDurationMs,
        Instant createdAt) {

    static DownloadEventListItem from(DownloadEventListRow row) {
        return new DownloadEventListItem(
                row.eventId(), row.agentId(), row.hostname(), row.sha256(), row.filename(),
                row.mimeType(), row.fileSize(), row.decision(), row.decisionSource(),
                row.cacheHit(), row.holdDurationMs(), row.createdAt());
    }
}
