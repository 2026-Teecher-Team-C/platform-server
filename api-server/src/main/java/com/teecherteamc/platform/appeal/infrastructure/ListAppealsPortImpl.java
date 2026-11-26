package com.teecherteamc.platform.appeal.infrastructure;

import com.teecherteamc.platform.appeal.domain.AppealRow;
import com.teecherteamc.platform.appeal.domain.ListAppealsPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * appeals에 호스트명(agents→devices)·파일명과 차단 시각(download_events, 없으면 null)·결정자(admin_users)·
 * 활성 블랙리스트 여부를 붙여 읽는다. download_events는 event_id가 유일해 LEFT JOIN해도 행이 늘지 않는다.
 */
@Component
class ListAppealsPortImpl implements ListAppealsPort {

    private static final String SELECT =
            """
            SELECT ap.appeal_id, ap.status, ap.created_at, ap.decided_at, d.hostname, e.filename, ap.sha256,
                   ap.evidence_kind, ap.threat, ap.reason, ap.event_id, ap.agent_id, ap.decision_note,
                   u.username AS decided_by_name, e.created_at AS blocked_at,
                   EXISTS(SELECT 1 FROM hash_blacklist b
                          WHERE b.hash_type = 'SHA256' AND b.hash_value = ap.sha256 AND b.is_active) AS blacklisted
            FROM appeals ap
            JOIN agents a ON a.agent_id = ap.agent_id
            JOIN devices d ON d.device_id = a.device_id
            LEFT JOIN download_events e ON e.event_id = ap.event_id
            LEFT JOIN admin_users u ON u.admin_id = ap.decided_by
            """;

    private static final RowMapper<AppealRow> MAPPER = (rs, rowNum) -> new AppealRow(
            UUID.fromString(rs.getString("appeal_id")),
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant(),
            instant(rs.getTimestamp("decided_at")),
            rs.getString("hostname"),
            rs.getString("filename"),
            rs.getString("sha256"),
            rs.getString("evidence_kind"),
            rs.getString("threat"),
            rs.getString("reason"),
            UUID.fromString(rs.getString("event_id")),
            UUID.fromString(rs.getString("agent_id")),
            rs.getString("decision_note"),
            rs.getString("decided_by_name"),
            rs.getBoolean("blacklisted"),
            instant(rs.getTimestamp("blocked_at")));

    private final JdbcTemplate jdbcTemplate;

    ListAppealsPortImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<AppealRow> list(String status, long offset, int limit) {
        StringBuilder sql = new StringBuilder(SELECT);
        List<Object> params = new ArrayList<>();
        if (status != null) {
            sql.append(" WHERE ap.status = ?");
            params.add(status);
        }
        sql.append(" ORDER BY ap.created_at DESC, ap.appeal_id DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);
        return jdbcTemplate.query(sql.toString(), MAPPER, params.toArray());
    }

    @Override
    public long count(String status) {
        // list()와 같은 조인(agents·devices는 INNER)을 거쳐야 목록에 안 나오는 행이 개수에 섞이지 않는다.
        String sql = "SELECT COUNT(*) FROM appeals ap JOIN agents a ON a.agent_id = ap.agent_id"
                + " JOIN devices d ON d.device_id = a.device_id" + (status == null ? "" : " WHERE ap.status = ?");
        Long total = status == null
                ? jdbcTemplate.queryForObject(sql, Long.class)
                : jdbcTemplate.queryForObject(sql, Long.class, status);
        return total == null ? 0 : total;
    }

    @Override
    public Optional<AppealRow> findById(UUID appealId) {
        return jdbcTemplate.query(SELECT + " WHERE ap.appeal_id = ?", MAPPER, appealId).stream().findFirst();
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
