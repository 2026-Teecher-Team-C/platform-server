package com.teecherteamc.platform.event.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 차단(BLOCKED·FAIL_CLOSE)으로 확정된 다운로드 이벤트가 저장됐다는 도메인 이벤트. 콘솔 SSE 스트림이 이 값을 받아
 * 구독자에게 보낸다.
 *
 * <p>hostname은 담지 않는다. download_events에 없는 값이라 받는 쪽(리스너)이 커밋 뒤에 조회해서 채운다.
 * createdAt은 DB가 채우는 값이라 저장 직후엔 비어 있을 수 있어서, 이벤트가 만들어진 시각을 occurredAt으로 따로 둔다.
 */
public record DownloadBlockedEvent(
        UUID eventId,
        UUID agentId,
        String sha256,
        String filename,
        String mimeType,
        Long fileSize,
        String decision,
        String decisionSource,
        boolean cacheHit,
        Integer holdDurationMs,
        Instant occurredAt) {

    public static DownloadBlockedEvent from(DownloadEvent event, Instant occurredAt) {
        return new DownloadBlockedEvent(
                event.getEventId(),
                event.getAgentId(),
                event.getSha256(),
                event.getFilename(),
                event.getMimeType(),
                event.getFileSize(),
                event.getDecision(),
                event.getDecisionSource(),
                Boolean.TRUE.equals(event.getCacheHit()),
                event.getHoldDurationMs(),
                occurredAt);
    }
}
