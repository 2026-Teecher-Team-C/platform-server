package com.teecherteamc.platform.quarantine.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * {@link QuarantineListRow} 필드 전부 + 상세 화면에서만 필요한 필드(download_events에서 조인한
 * requestHost/url). S3 위치는 목록과 같은 이유로 포함하지 않는다.
 */
public record QuarantineDetailRow(
        UUID quarantineId,
        UUID eventId,
        UUID agentId,
        String hostname,
        String sha256,
        String originalFilename,
        long fileSize,
        String status,
        Instant quarantinedAt,
        Instant restoredAt,
        String requestHost,
        String url) {
}
