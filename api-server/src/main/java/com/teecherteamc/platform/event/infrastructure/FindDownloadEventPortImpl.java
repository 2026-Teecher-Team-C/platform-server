package com.teecherteamc.platform.event.infrastructure;

import com.teecherteamc.platform.event.domain.DownloadEventDetailRow;
import com.teecherteamc.platform.event.domain.FindDownloadEventPort;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * download_events를 agents/devices와 조인해 상세 행 하나를 읽는 어댑터. {@link ListDownloadEventsPortImpl}과
 * 같은 조인에, 상세 화면에만 필요한 컬럼을 더 셀렉트한다.
 */
@Component
class FindDownloadEventPortImpl implements FindDownloadEventPort {

    private static final String SQL =
            """
            SELECT e.event_id, e.agent_id, d.hostname, e.sha256, e.filename, e.mime_type,
                   e.file_size, e.decision, e.decision_source, e.cache_hit, e.hold_duration_ms,
                   e.created_at, e.request_host, e.url, e.bytes_uploaded, e.held_at, e.decided_at
            FROM download_events e
            JOIN agents a ON a.agent_id = e.agent_id
            JOIN devices d ON d.device_id = a.device_id
            WHERE e.event_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    FindDownloadEventPortImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<DownloadEventDetailRow> findById(UUID eventId) {
        List<DownloadEventDetailRow> rows = jdbcTemplate.query(
                SQL,
                (rs, rowNum) -> {
                    Timestamp decidedAt = rs.getTimestamp("decided_at");
                    return new DownloadEventDetailRow(
                            UUID.fromString(rs.getString("event_id")),
                            UUID.fromString(rs.getString("agent_id")),
                            rs.getString("hostname"),
                            rs.getString("sha256"),
                            rs.getString("filename"),
                            rs.getString("mime_type"),
                            rs.getObject("file_size", Long.class),
                            rs.getString("decision"),
                            rs.getString("decision_source"),
                            rs.getBoolean("cache_hit"),
                            rs.getObject("hold_duration_ms", Integer.class),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getString("request_host"),
                            rs.getString("url"),
                            rs.getLong("bytes_uploaded"),
                            rs.getTimestamp("held_at").toInstant(),
                            decidedAt == null ? null : decidedAt.toInstant());
                },
                eventId);
        return rows.stream().findFirst();
    }
}
