package com.teecherteamc.platform.event.application;

import com.teecherteamc.platform.event.domain.DownloadBlockedEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * SSE로 내려가는 data. console/src/lib/api/events.ts의 DownloadEventListItem과 같은 모양이다. createdAt에는
 * DB 값 대신 이벤트가 만들어진 시각(occurredAt)을 넣는다.
 */
public record DownloadEventStreamMessage(
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

    static DownloadEventStreamMessage of(DownloadBlockedEvent event, String hostname) {
        return new DownloadEventStreamMessage(
                event.eventId(), event.agentId(), hostname, event.sha256(), event.filename(),
                event.mimeType(), event.fileSize(), event.decision(), event.decisionSource(),
                event.cacheHit(), event.holdDurationMs(), event.occurredAt());
    }
}
