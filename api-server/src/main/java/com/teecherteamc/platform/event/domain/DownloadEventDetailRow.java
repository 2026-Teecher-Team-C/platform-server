package com.teecherteamc.platform.event.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * download_events + devices.hostname 조인 결과 1행(상세용). {@link DownloadEventListRow}의
 * 목록 필드 전부에 상세 화면에서만 필요한 필드(requestHost/url/bytesUploaded/heldAt/decidedAt)를
 * 더한 읽기 전용 행 — JPA 엔티티가 아니다.
 */
public record DownloadEventDetailRow(
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
        Instant createdAt,
        String requestHost,
        String url,
        long bytesUploaded,
        Instant heldAt,
        Instant decidedAt) {
}
