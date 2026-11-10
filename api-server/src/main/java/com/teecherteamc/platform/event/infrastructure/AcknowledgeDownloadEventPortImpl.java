package com.teecherteamc.platform.event.infrastructure;

import com.teecherteamc.platform.event.domain.AcknowledgeDownloadEventPort;
import com.teecherteamc.platform.event.domain.DownloadEventAcknowledgement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class AcknowledgeDownloadEventPortImpl implements AcknowledgeDownloadEventPort {

    private final JdbcTemplate jdbcTemplate;

    AcknowledgeDownloadEventPortImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<Boolean> isBlocked(UUID eventId) {
        List<Boolean> rows = jdbcTemplate.query(
                "SELECT decision IN ('BLOCKED', 'FAIL_CLOSE') AS blocked FROM download_events WHERE event_id = ?",
                // decision이 NULL이면 IN 결과도 NULL이다. getBoolean()이 NULL을 false로 돌려주므로 "차단 아님"이 된다.
                (rs, rowNum) -> rs.getBoolean("blocked"),
                eventId);
        return rows.stream().findFirst();
    }

    @Override
    public boolean insertIfAbsent(UUID eventId, UUID adminId, Instant acknowledgedAt) {
        int inserted = jdbcTemplate.update(
                """
                INSERT INTO download_event_acknowledgements (event_id, acknowledged_by, acknowledged_at)
                VALUES (?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """,
                eventId, adminId, Timestamp.from(acknowledgedAt));
        return inserted == 1;
    }

    @Override
    public DownloadEventAcknowledgement find(UUID eventId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT ack.event_id, u.username, ack.acknowledged_at
                FROM download_event_acknowledgements ack
                JOIN admin_users u ON u.admin_id = ack.acknowledged_by
                WHERE ack.event_id = ?
                """,
                (rs, rowNum) -> new DownloadEventAcknowledgement(
                        UUID.fromString(rs.getString("event_id")),
                        rs.getString("username"),
                        rs.getTimestamp("acknowledged_at").toInstant()),
                eventId);
    }
}
