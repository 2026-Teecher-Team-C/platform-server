package com.teecherteamc.platform.quarantine.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * quarantine_files + devices.hostname 조인 결과 1행. 목록 전용 읽기 모델 — 애그리거트가 아니다.
 * S3 위치(s3_bucket/s3_key)는 원본 다운로드 경로가 노출되면 안 되므로 애초에 포함하지 않는다.
 */
public record QuarantineListRow(
        UUID quarantineId,
        UUID eventId,
        UUID agentId,
        String hostname,
        String sha256,
        String originalFilename,
        long fileSize,
        String status,
        Instant quarantinedAt,
        Instant restoredAt) {
}
