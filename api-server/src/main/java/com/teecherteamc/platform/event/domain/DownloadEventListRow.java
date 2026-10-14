package com.teecherteamc.platform.event.domain;

import java.time.Instant;
import java.util.UUID;

/** download_events + devices.hostname 조인 결과 1행. 목록 전용 읽기 모델 — JPA 엔티티가 아니다. */
public record DownloadEventListRow(
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
}
