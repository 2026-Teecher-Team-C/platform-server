package com.teecherteamc.platform.quarantine.infrastructure;

import com.teecherteamc.platform.quarantine.domain.ListQuarantineFilesPort;
import com.teecherteamc.platform.quarantine.domain.QuarantineListRow;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * quarantine_files를 agents/devices와 조인해 hostname까지 한 번에 읽는 어댑터. fleet 컨텍스트의
 * JPA 애그리거트는 안 거친다 — 목록 1건당 hostname 하나 보여주려고 애그리거트 전체를 로드할 이유가
 * 없다. S3 위치(s3_bucket/s3_key)는 애초에 셀렉트하지 않는다(원본 다운로드 경로 비노출 요구사항).
 */
@Component
class ListQuarantineFilesPortImpl implements ListQuarantineFilesPort {

    private static final String BASE_SQL =
            """
            SELECT q.quarantine_id, q.event_id, q.agent_id, d.hostname, q.sha256, q.original_filename,
                   q.file_size, q.status, q.quarantined_at, q.restored_at
            FROM quarantine_files q
            JOIN agents a ON a.agent_id = q.agent_id
            JOIN devices d ON d.device_id = a.device_id
            WHERE 1 = 1
            """;

    private static final String COUNT_SQL =
            """
            SELECT COUNT(*)
            FROM quarantine_files q
            JOIN agents a ON a.agent_id = q.agent_id
            JOIN devices d ON d.device_id = a.device_id
            WHERE 1 = 1
            """;

    private final JdbcTemplate jdbcTemplate;

    ListQuarantineFilesPortImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<QuarantineListRow> list(String status, UUID agentId, long offset, int limit) {
        StringBuilder sql = new StringBuilder(BASE_SQL);
        List<Object> params = new ArrayList<>();
        appendFilters(sql, params, status, agentId);
        sql.append(" ORDER BY q.quarantined_at DESC, q.quarantine_id DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);
        return jdbcTemplate.query(
                sql.toString(),
                (rs, rowNum) -> {
                    Timestamp restoredAt = rs.getTimestamp("restored_at");
                    return new QuarantineListRow(
                            UUID.fromString(rs.getString("quarantine_id")),
                            UUID.fromString(rs.getString("event_id")),
                            UUID.fromString(rs.getString("agent_id")),
                            rs.getString("hostname"),
                            rs.getString("sha256"),
                            rs.getString("original_filename"),
                            rs.getLong("file_size"),
                            rs.getString("status"),
                            rs.getTimestamp("quarantined_at").toInstant(),
                            restoredAt == null ? null : restoredAt.toInstant());
                },
                params.toArray());
    }

    @Override
    public long count(String status, UUID agentId) {
        // list()와 같은 조인을 거쳐야 목록에 안 나오는 행이 개수에 섞이지 않는다.
        StringBuilder sql = new StringBuilder(COUNT_SQL);
        List<Object> params = new ArrayList<>();
        appendFilters(sql, params, status, agentId);
        Long total = jdbcTemplate.queryForObject(sql.toString(), Long.class, params.toArray());
        return total == null ? 0 : total;
    }

    private static void appendFilters(StringBuilder sql, List<Object> params, String status, UUID agentId) {
        if (status != null) {
            sql.append(" AND q.status = ?");
            params.add(status);
        }
        if (agentId != null) {
            sql.append(" AND q.agent_id = ?");
            params.add(agentId);
        }
    }
}
