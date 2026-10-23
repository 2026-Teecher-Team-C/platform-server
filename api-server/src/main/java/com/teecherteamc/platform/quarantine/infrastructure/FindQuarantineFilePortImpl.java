package com.teecherteamc.platform.quarantine.infrastructure;

import com.teecherteamc.platform.quarantine.domain.FindQuarantineFilePort;
import com.teecherteamc.platform.quarantine.domain.QuarantineDetailRow;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@link ListQuarantineFilesPortImpl}의 조인(quarantine_files+agents+devices)에 download_events를
 * 더 조인해서 요청 호스트/URL까지 한 번에 읽는 어댑터. S3 위치는 애초에 셀렉트하지 않는다.
 */
@Component
class FindQuarantineFilePortImpl implements FindQuarantineFilePort {

    private static final String SQL =
            """
            SELECT q.quarantine_id, q.event_id, q.agent_id, d.hostname, q.sha256, q.original_filename,
                   q.file_size, q.status, q.quarantined_at, q.restored_at, e.request_host, e.url
            FROM quarantine_files q
            JOIN agents a ON a.agent_id = q.agent_id
            JOIN devices d ON d.device_id = a.device_id
            JOIN download_events e ON e.event_id = q.event_id
            WHERE q.quarantine_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    FindQuarantineFilePortImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<QuarantineDetailRow> findById(UUID quarantineId) {
        List<QuarantineDetailRow> rows = jdbcTemplate.query(
                SQL,
                (rs, rowNum) -> {
                    Timestamp restoredAt = rs.getTimestamp("restored_at");
                    return new QuarantineDetailRow(
                            UUID.fromString(rs.getString("quarantine_id")),
                            UUID.fromString(rs.getString("event_id")),
                            UUID.fromString(rs.getString("agent_id")),
                            rs.getString("hostname"),
                            rs.getString("sha256"),
                            rs.getString("original_filename"),
                            rs.getLong("file_size"),
                            rs.getString("status"),
                            rs.getTimestamp("quarantined_at").toInstant(),
                            restoredAt == null ? null : restoredAt.toInstant(),
                            rs.getString("request_host"),
                            rs.getString("url"));
                },
                quarantineId);
        return rows.stream().findFirst();
    }
}
