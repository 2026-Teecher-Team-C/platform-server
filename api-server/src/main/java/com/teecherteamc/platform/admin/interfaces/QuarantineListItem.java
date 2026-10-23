package com.teecherteamc.platform.admin.interfaces;

import com.teecherteamc.platform.quarantine.domain.QuarantineListRow;
import java.time.Instant;
import java.util.UUID;

/** console/src/lib/api/quarantine.ts의 QuarantineListItem과 1:1 대응하는 응답 DTO. */
public record QuarantineListItem(
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

    static QuarantineListItem from(QuarantineListRow row) {
        return new QuarantineListItem(
                row.quarantineId(), row.eventId(), row.agentId(), row.hostname(), row.sha256(),
                row.originalFilename(), row.fileSize(), row.status(), row.quarantinedAt(),
                row.restoredAt());
    }
}
