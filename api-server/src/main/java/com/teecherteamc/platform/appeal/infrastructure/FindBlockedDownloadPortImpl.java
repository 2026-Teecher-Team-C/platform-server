package com.teecherteamc.platform.appeal.infrastructure;

import com.teecherteamc.platform.appeal.domain.BlockedDownload;
import com.teecherteamc.platform.appeal.domain.FindBlockedDownloadPort;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** download_events에서 소명 화면에 필요한 두 컬럼만 읽는다. event 컨텍스트의 애그리거트는 건드리지 않는다. */
@Component
class FindBlockedDownloadPortImpl implements FindBlockedDownloadPort {

    private static final String SQL =
            "SELECT decided_at, filename FROM download_events WHERE event_id = ? AND agent_id = ?";
    private static final String OWNED_BLOCK_SQL = """
            SELECT EXISTS (
                SELECT 1 FROM download_events
                WHERE event_id = ? AND agent_id = ? AND decision = 'BLOCKED' AND (sha256 = ? OR sha256 IS NULL)
            )
            """;

    private final JdbcTemplate jdbcTemplate;

    FindBlockedDownloadPortImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<BlockedDownload> find(UUID eventId, UUID agentId) {
        List<BlockedDownload> rows = jdbcTemplate.query(
                SQL,
                (rs, rowNum) -> {
                    Timestamp decidedAt = rs.getTimestamp("decided_at");
                    return new BlockedDownload(
                            decidedAt == null ? null : decidedAt.toInstant(), rs.getString("filename"));
                },
                eventId, agentId);
        return rows.stream().findFirst();
    }

    @Override
    public boolean isOwnedBlock(UUID eventId, UUID agentId, String sha256) {
        return Boolean.TRUE.equals(
                jdbcTemplate.queryForObject(OWNED_BLOCK_SQL, Boolean.class, eventId, agentId, sha256));
    }
}
