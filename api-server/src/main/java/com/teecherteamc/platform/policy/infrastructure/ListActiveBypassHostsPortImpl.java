package com.teecherteamc.platform.policy.infrastructure;

import com.teecherteamc.platform.policy.domain.ActiveBypassHost;
import com.teecherteamc.platform.policy.domain.BypassCategory;
import com.teecherteamc.platform.policy.domain.ListActiveBypassHostsPort;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 활성 바이패스 호스트를 JdbcTemplate으로 전부 읽는 어댑터. 에이전트 GetPolicy 응답의 원본이다. */
@Component
class ListActiveBypassHostsPortImpl implements ListActiveBypassHostsPort {

    private static final String SQL =
            """
            SELECT domain_pattern, category
            FROM bypass_domains
            WHERE is_active = TRUE
            ORDER BY domain_pattern
            """;

    private final JdbcTemplate jdbcTemplate;

    ListActiveBypassHostsPortImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<ActiveBypassHost> listAll() {
        return jdbcTemplate.query(
                SQL,
                (rs, rowNum) -> new ActiveBypassHost(
                        rs.getString("domain_pattern"), toCategory(rs.getString("category"))));
    }

    /** DB 값이 enum에 없으면 요청 검증이 아닌 데이터 이상이므로 BypassCategory.from()을 쓰지 않고 바로 실패시킨다. */
    private static BypassCategory toCategory(String raw) {
        try {
            return BypassCategory.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalStateException("알 수 없는 bypass category: " + raw, e);
        }
    }
}
