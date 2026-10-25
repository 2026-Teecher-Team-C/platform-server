package com.teecherteamc.platform.hashlist.infrastructure;

import com.teecherteamc.platform.hashlist.domain.BlacklistListRow;
import com.teecherteamc.platform.hashlist.domain.ListBlacklistPort;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * hash_blacklist를 admin_users와 조인해 username까지 한 번에 읽는 어댑터. admin 컨텍스트의
 * JPA 애그리거트(AdminUser)는 안 거친다 — 목록 1건당 username 하나 보여주려고 애그리거트를
 * 로드할 이유가 없다.
 *
 * 등록자가 없는 항목(added_by가 NULL)도 나와야 해서 LEFT JOIN이다.
 */
@Component
class ListBlacklistPortImpl implements ListBlacklistPort {

    private static final String BASE_SQL =
            """
            SELECT b.hash_type, b.hash_value, b.reason, b.severity, b.source,
                   b.added_by, u.username AS added_by_username,
                   b.is_active, b.created_at, b.deactivated_at
            FROM hash_blacklist b
            LEFT JOIN admin_users u ON u.admin_id = b.added_by
            WHERE 1 = 1
            """;

    // admin_users.admin_id가 PK라서 LEFT JOIN이 행을 늘리지 않는다. 그래서 개수는 조인 없이 센다.
    private static final String COUNT_SQL =
            """
            SELECT COUNT(*)
            FROM hash_blacklist b
            WHERE 1 = 1
            """;

    private final JdbcTemplate jdbcTemplate;

    ListBlacklistPortImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<BlacklistListRow> list(
            String hashValuePrefix, String severity, String source, boolean active, long offset, int limit) {
        StringBuilder sql = new StringBuilder(BASE_SQL);
        List<Object> params = new ArrayList<>();
        appendFilters(sql, params, hashValuePrefix, severity, source, active);
        sql.append(" ORDER BY b.created_at DESC, b.hash_value DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);
        return jdbcTemplate.query(
                sql.toString(),
                (rs, rowNum) -> {
                    String addedBy = rs.getString("added_by");
                    Timestamp deactivatedAt = rs.getTimestamp("deactivated_at");
                    return new BlacklistListRow(
                            rs.getString("hash_type"),
                            rs.getString("hash_value"),
                            rs.getString("reason"),
                            rs.getString("severity"),
                            rs.getString("source"),
                            addedBy == null ? null : UUID.fromString(addedBy),
                            rs.getString("added_by_username"),
                            rs.getBoolean("is_active"),
                            rs.getTimestamp("created_at").toInstant(),
                            deactivatedAt == null ? null : deactivatedAt.toInstant());
                },
                params.toArray());
    }

    @Override
    public long count(String hashValuePrefix, String severity, String source, boolean active) {
        StringBuilder sql = new StringBuilder(COUNT_SQL);
        List<Object> params = new ArrayList<>();
        appendFilters(sql, params, hashValuePrefix, severity, source, active);
        Long total = jdbcTemplate.queryForObject(sql.toString(), Long.class, params.toArray());
        return total == null ? 0 : total;
    }

    private static void appendFilters(
            StringBuilder sql, List<Object> params,
            String hashValuePrefix, String severity, String source, boolean active) {
        // active는 항상 건다. 기본값(활성만) 처리는 컨트롤러가 한다.
        sql.append(" AND b.is_active = ?");
        params.add(active);
        if (hashValuePrefix != null) {
            // 호출하는 쪽이 소문자 hex만 넘긴다. LIKE의 특수문자(%, _)가 섞이면 안 된다.
            sql.append(" AND b.hash_value LIKE ?");
            params.add(hashValuePrefix + "%");
        }
        if (severity != null) {
            sql.append(" AND b.severity = ?");
            params.add(severity);
        }
        if (source != null) {
            sql.append(" AND b.source = ?");
            params.add(source);
        }
    }
}