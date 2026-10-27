package com.teecherteamc.platform.audit.infrastructure;

import com.teecherteamc.platform.audit.domain.AuditAction;
import com.teecherteamc.platform.audit.domain.AuditLogRow;
import com.teecherteamc.platform.audit.domain.ListAuditLogsPort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** audit_logs를 JdbcTemplate으로 직접 읽는 어댑터. 조회 조건은 appendFilters 한 곳에서 만든다. */
@Component
class ListAuditLogsPortImpl implements ListAuditLogsPort {

    // ip_address는 INET이라 host()로 마스크 없는 문자열로 바꿔 읽는다.
    private static final String SELECT_SQL =
            """
            SELECT l.log_id, l.actor_id, a.username, a.role, l.action, l.target_type, l.target_id, l.detail,
                   host(l.ip_address) AS ip, l.created_at
            FROM audit_logs l
            LEFT JOIN admin_users a ON a.admin_id = l.actor_id
            WHERE 1 = 1
            """;

    private static final String COUNT_SQL =
            """
            SELECT COUNT(*)
            FROM audit_logs l
            WHERE 1 = 1
            """;

    private final JdbcTemplate jdbcTemplate;

    ListAuditLogsPortImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<AuditLogRow> list(Filter filter, long offset, int limit) {
        StringBuilder sql = new StringBuilder(SELECT_SQL);
        List<Object> params = new ArrayList<>();
        appendFilters(sql, params, filter);
        // 같은 시각에 남은 행은 나중에 들어간 것(log_id 큰 것)을 먼저 보여준다.
        sql.append(" ORDER BY l.created_at DESC, l.log_id DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> toRow(rs), params.toArray());
    }

    @Override
    public long count(Filter filter) {
        StringBuilder sql = new StringBuilder(COUNT_SQL);
        List<Object> params = new ArrayList<>();
        appendFilters(sql, params, filter);
        Long total = jdbcTemplate.queryForObject(sql.toString(), Long.class, params.toArray());
        return total == null ? 0 : total;
    }

    private static void appendFilters(StringBuilder sql, List<Object> params, Filter filter) {
        if (filter.actorId() != null) {
            sql.append(" AND l.actor_id = ?");
            params.add(filter.actorId());
        }
        if (filter.action() != null) {
            sql.append(" AND l.action = ?");
            params.add(filter.action());
        }
        if (filter.targetType() != null) {
            sql.append(" AND l.target_type = ?");
            params.add(filter.targetType());
        }
        if (filter.from() != null) {
            sql.append(" AND l.created_at >= ?");
            params.add(Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            sql.append(" AND l.created_at < ?");
            params.add(Timestamp.from(filter.to()));
        }
        if (filter.dangerousOnly()) {
            List<String> names = List.copyOf(AuditAction.dangerousNames());
            sql.append(" AND l.action IN (").append(String.join(", ", Collections.nCopies(names.size(), "?")))
                    .append(")");
            params.addAll(names);
        }
    }

    private static AuditLogRow toRow(ResultSet rs) throws SQLException {
        return new AuditLogRow(
                rs.getLong("log_id"),
                rs.getObject("actor_id", UUID.class),
                rs.getString("username"),
                rs.getString("role"),
                rs.getString("action"),
                rs.getString("target_type"),
                rs.getString("target_id"),
                rs.getString("detail"),
                rs.getString("ip"),
                rs.getTimestamp("created_at").toInstant());
    }
}
