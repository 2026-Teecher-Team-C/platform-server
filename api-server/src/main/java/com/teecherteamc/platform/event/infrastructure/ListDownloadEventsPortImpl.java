package com.teecherteamc.platform.event.infrastructure;

import com.teecherteamc.platform.event.domain.DownloadEventListRow;
import com.teecherteamc.platform.event.domain.ListDownloadEventsPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * download_events를 agents/devices와 조인해 hostname까지 한 번에 읽는 어댑터. fleet 컨텍스트의
 * JPA 애그리거트(Agent/Device)는 안 거친다 — 목록 1건당 hostname 하나 보여주려고 애그리거트 전체를
 * 로드할 이유가 없다.
 */
@Component
class ListDownloadEventsPortImpl implements ListDownloadEventsPort {

    private static final String BASE_SQL =
            """
            SELECT e.event_id, e.agent_id, d.hostname, e.sha256, e.filename, e.mime_type,
                   e.file_size, e.decision, e.decision_source, e.cache_hit, e.hold_duration_ms,
                   e.created_at
            FROM download_events e
            JOIN agents a ON a.agent_id = e.agent_id
            JOIN devices d ON d.device_id = a.device_id
            WHERE 1 = 1
            """;

    private final JdbcTemplate jdbcTemplate;

    ListDownloadEventsPortImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<DownloadEventListRow> list(UUID agentId, String decision, Instant from, Instant to, int limit) {
        StringBuilder sql = new StringBuilder(BASE_SQL);
        List<Object> params = new ArrayList<>();

        if (agentId != null) {
            sql.append(" AND e.agent_id = ?");
            params.add(agentId);
        }
        if (decision != null) {
            sql.append(" AND e.decision = ?");
            params.add(decision);
        }
        if (from != null) {
            sql.append(" AND e.created_at >= ?");
            params.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND e.created_at < ?");
            params.add(Timestamp.from(to));
        }
        sql.append(" ORDER BY e.created_at DESC, e.event_id DESC LIMIT ?");
        params.add(limit);

        return jdbcTemplate.query(
                sql.toString(),
                (rs, rowNum) -> new DownloadEventListRow(
                        UUID.fromString(rs.getString("event_id")),
                        UUID.fromString(rs.getString("agent_id")),
                        rs.getString("hostname"),
                        rs.getString("sha256"),
                        rs.getString("filename"),
                        rs.getString("mime_type"),
                        rs.getObject("file_size", Long.class),
                        rs.getString("decision"),
                        rs.getString("decision_source"),
                        // cache_hit은 nullable이지만 getBoolean()은 SQL NULL을 false로 돌려준다 —
                        // 콘솔 타입(cacheHit: boolean, non-null)과 맞아서 의도적으로 그대로 둔다.
                        rs.getBoolean("cache_hit"),
                        rs.getObject("hold_duration_ms", Integer.class),
                        rs.getTimestamp("created_at").toInstant()),
                params.toArray());
    }
}
