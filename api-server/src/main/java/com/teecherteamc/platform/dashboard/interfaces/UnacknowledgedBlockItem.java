package com.teecherteamc.platform.dashboard.interfaces;

import com.teecherteamc.platform.dashboard.domain.UnacknowledgedBlockRow;
import java.time.Instant;
import java.util.UUID;

/** 콘솔 UnacknowledgedBlock 타입(console/src/lib/api/dashboard.ts)과 같은 모양. */
public record UnacknowledgedBlockItem(
        UUID eventId,
        Instant createdAt,
        UUID agentId,
        String hostname,
        String filename,
        String reasonType,
        String topRuleName,
        int extraRuleCount,
        String severity) {

    static UnacknowledgedBlockItem from(UnacknowledgedBlockRow row) {
        return new UnacknowledgedBlockItem(row.eventId(), row.createdAt(), row.agentId(), row.hostname(),
                row.filename(), row.reasonType(), row.topRuleName(), row.extraRuleCount(), row.severity());
    }
}
