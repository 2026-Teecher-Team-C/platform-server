package com.teecherteamc.platform.hashlist.infrastructure;

import com.teecherteamc.platform.hashlist.domain.ListWhitelistPort;
import com.teecherteamc.platform.hashlist.domain.WhitelistRow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** hash_whitelist를 JdbcTemplate으로 직접 읽는 어댑터. 조회 조건은 appendFilters 한 곳에서 만든다. */
@Component
class ListWhitelistPortImpl implements ListWhitelistPort {

    private static final String SELECT_SQL =
            """
            SELECT w.hash_type, w.hash_value, w.reason, w.origin_quarantine_id, w.added_by, a.username,
                   w.is_active, w.expires_at, w.created_at
            FROM hash_whitelist w
            LEFT JOIN admin_users a ON a.admin_id = w.added_by
            WHERE 1 = 1
            """;

    private static final String COUNT_SQL =
            """
            SELECT COUNT(*)
            FROM hash_whitelist w
            WHERE 1 = 1
            """;

    private final JdbcTemplate jdbcTemplate;

    ListWhitelistPortImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<WhitelistRow> list(Filter filter, long offset, int limit) {
        StringBuilder sql = new StringBuilder(SELECT_SQL);
        List<Object> params = new ArrayList<>();
        appendFilters(sql, params, filter);
        sql.append(" ORDER BY w.created_at DESC, w.hash_value DESC LIMIT ? OFFSET ?");
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
        sql.append(" AND w.is_active = ?");
        params.add(filter.active());
        if (!filter.includeExpired()) {
            // WhitelistEntry.isEffectiveAt과 같은 규칙: 만료 시각 정각은 만료된 것으로 본다.
            sql.append(" AND (w.expires_at IS NULL OR w.expires_at > ?)");
            params.add(Timestamp.from(filter.now()));
        }
        if (filter.origin() != null) {
            sql.append("RESTORED".equals(filter.origin())
                    ? " AND w.origin_quarantine_id IS NOT NULL"
                    : " AND w.origin_quarantine_id IS NULL");
        }
        if (filter.hashPrefix() != null) {
            // 사용자가 넣은 %, _, \는 문자 그대로 찾도록 이스케이프한다.
            sql.append(" AND w.hash_value LIKE ? ESCAPE '\\'");
            params.add(escapeLike(filter.hashPrefix()) + "%");
        }
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static WhitelistRow toRow(ResultSet rs) throws SQLException {
        Timestamp expiresAt = rs.getTimestamp("expires_at");
        return new WhitelistRow(
                rs.getString("hash_type"),
                rs.getString("hash_value"),
                rs.getString("reason"),
                rs.getObject("origin_quarantine_id", UUID.class),
                rs.getObject("added_by", UUID.class),
                rs.getString("username"),
                rs.getBoolean("is_active"),
                expiresAt == null ? null : expiresAt.toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }
}
