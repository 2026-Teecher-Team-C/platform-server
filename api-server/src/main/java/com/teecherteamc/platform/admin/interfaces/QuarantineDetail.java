package com.teecherteamc.platform.admin.interfaces;

import com.teecherteamc.platform.quarantine.domain.QuarantineDetailRow;
import java.time.Instant;
import java.util.UUID;

/** console/src/lib/api/quarantine.ts의 getQuarantineFile() 응답 모양과 1:1 대응하는 DTO. */
public record QuarantineDetail(
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
        DownloadEventRef downloadEvent,
        AgentRef agent) {

    public record DownloadEventRef(UUID eventId, String requestHost, String url) {
    }

    public record AgentRef(UUID agentId, String hostname) {
    }

    static QuarantineDetail from(QuarantineDetailRow row) {
        return new QuarantineDetail(
                row.quarantineId(), row.eventId(), row.agentId(), row.hostname(), row.sha256(),
                row.originalFilename(), row.fileSize(), row.status(), row.quarantinedAt(),
                row.restoredAt(),
                new DownloadEventRef(row.eventId(), row.requestHost(), row.url()),
                new AgentRef(row.agentId(), row.hostname()));
    }
}
