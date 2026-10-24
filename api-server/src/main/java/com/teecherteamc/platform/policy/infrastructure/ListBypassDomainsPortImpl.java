package com.teecherteamc.platform.policy.infrastructure;

import com.teecherteamc.platform.policy.domain.BypassDomainRow;
import com.teecherteamc.platform.policy.domain.ListBypassDomainsPort;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** bypass_domains를 JdbcTemplate으로 직접 읽는 어댑터. 조회 조건은 appendFilters 한 곳에서 만든다. */
@Component
class ListBypassDomainsPortImpl implements ListBypassDomainsPort {

    private static final String BASE_SQL =
            """
            SELECT bypass_id, domain_pattern, category, reason, is_active, created_at
            FROM bypass_domains
            WHERE 1 = 1
            """;

    private static final String COUNT_SQL =
            """
            SELECT COUNT(*)
            FROM bypass_domains
            WHERE 1 = 1
            """;

    private final JdbcTemplate jdbcTemplate;

    ListBypassDomainsPortImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<BypassDomainRow> list(Boolean active, String category, String hostKeyword, long offset, int limit) {
        StringBuilder sql = new StringBuilder(BASE_SQL);
        List<Object> params = new ArrayList<>();
        appendFilters(sql, params, active, category, hostKeyword);
        sql.append(" ORDER BY created_at DESC, bypass_id DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);
        return jdbcTemplate.query(
                sql.toString(),
                (rs, rowNum) -> new BypassDomainRow(
                        UUID.fromString(rs.getString("bypass_id")),
                        rs.getString("domain_pattern"),
                        rs.getString("category"),
                        rs.getString("reason"),
                        rs.getBoolean("is_active"),
                        rs.getTimestamp("created_at").toInstant()),
                params.toArray());
    }

    @Override
    public long count(Boolean active, String category, String hostKeyword) {
        StringBuilder sql = new StringBuilder(COUNT_SQL);
        List<Object> params = new ArrayList<>();
        appendFilters(sql, params, active, category, hostKeyword);
        Long total = jdbcTemplate.queryForObject(sql.toString(), Long.class, params.toArray());
        return total == null ? 0 : total;
    }

    private static void appendFilters(
            StringBuilder sql, List<Object> params, Boolean active, String category, String hostKeyword) {
        if (active != null) {
            sql.append(" AND is_active = ?");
            params.add(active);
        }
        if (category != null) {
            sql.append(" AND category = ?");
            params.add(category);
        }
        if (hostKeyword != null) {
            // 사용자가 넣은 %, _, \는 문자 그대로 찾도록 이스케이프한다.
            sql.append(" AND LOWER(domain_pattern) LIKE ? ESCAPE '\\'");
            params.add("%" + escapeLike(hostKeyword.toLowerCase()) + "%");
        }
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
