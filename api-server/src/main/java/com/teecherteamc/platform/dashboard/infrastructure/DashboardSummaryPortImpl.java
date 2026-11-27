package com.teecherteamc.platform.dashboard.infrastructure;

import com.teecherteamc.platform.dashboard.domain.DashboardPeriod;
import com.teecherteamc.platform.dashboard.domain.DashboardSummaryPort;
import com.teecherteamc.platform.dashboard.domain.SummaryCounts;
import com.teecherteamc.platform.common.DashboardReadJdbc;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 상단 카운터 SQL. 직전 기간과 이번 기간을 한 번에 읽고 FILTER로 나눈다.
 * ListDownloadEventsPortImpl·DashboardWidgetsPortImpl과 같은 agents/devices 조인을 거친다.
 */
@Component
class DashboardSummaryPortImpl implements DashboardSummaryPort {

    private static final String COUNTS_SQL =
            """
            WITH scoped AS (
                SELECT e.agent_id, e.decision, e.decision_source,
                       e.created_at >= :from AS in_period,
                       e.decision IN ('BLOCKED', 'FAIL_CLOSE') AS is_block
                FROM download_events e
                JOIN agents a ON a.agent_id = e.agent_id
                JOIN devices d ON d.device_id = a.device_id
                WHERE e.created_at >= :previousFrom AND e.created_at < :to
            )
            SELECT
                COUNT(*) FILTER (WHERE in_period AND is_block) AS blocked,
                COUNT(*) FILTER (WHERE NOT in_period AND is_block) AS blocked_previous,
                COUNT(*) FILTER (WHERE in_period AND decision = 'BLOCKED' AND decision_source = 'ENGINE') AS engine,
                COUNT(*) FILTER (WHERE in_period AND decision = 'BLOCKED' AND decision_source = 'CACHE') AS cache,
                COUNT(*) FILTER (WHERE in_period AND decision = 'BLOCKED' AND decision_source = 'BLACKLIST')
                    AS blacklist,
                COUNT(*) FILTER (WHERE in_period AND decision = 'FAIL_CLOSE') AS fail_close,
                -- 차단 PC 수는 차단 행(약 1%)에서만 따로 센다. 전체 행에 COUNT(DISTINCT)를 걸면 직전 기간까지
                -- 수백만 행을 정렬한다(V23, 2026-11-27 측정). 조건은 위 is_block·in_period와 같다.
                (SELECT COUNT(DISTINCT b.agent_id)
                 FROM download_events b
                 JOIN agents ba ON ba.agent_id = b.agent_id
                 JOIN devices bd ON bd.device_id = ba.device_id
                 WHERE b.decision IN ('BLOCKED', 'FAIL_CLOSE')
                   AND b.created_at >= :from AND b.created_at < :to) AS affected_agents,
                COUNT(*) FILTER (WHERE in_period AND decision IS NOT NULL AND decision <> 'BYPASSED') AS inspected
            FROM scoped
            """;

    private static final String TOTAL_AGENTS_SQL =
            """
            SELECT COUNT(*)
            FROM agents a
            JOIN devices d ON d.device_id = a.device_id
            WHERE a.status <> 'REVOKED'
            """;

    private final NamedParameterJdbcTemplate namedJdbc;
    private final JdbcTemplate jdbcTemplate;

    // 문장 타임아웃(10초)이 걸린 대시보드 읽기 전용 템플릿(DashboardReadJdbc).
    DashboardSummaryPortImpl(DashboardReadJdbc readJdbc) {
        this.namedJdbc = readJdbc.named();
        this.jdbcTemplate = readJdbc.jdbc();
    }

    @Override
    public SummaryCounts counts(DashboardPeriod period) {
        Instant previousFrom = period.from().minus(Duration.between(period.from(), period.to()));
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("previousFrom", Timestamp.from(previousFrom))
                .addValue("from", Timestamp.from(period.from()))
                .addValue("to", Timestamp.from(period.to()));
        return namedJdbc.queryForObject(COUNTS_SQL, params,
                (rs, rowNum) -> new SummaryCounts(
                        rs.getLong("blocked"),
                        rs.getLong("blocked_previous"),
                        rs.getLong("engine"),
                        rs.getLong("cache"),
                        rs.getLong("blacklist"),
                        rs.getLong("fail_close"),
                        rs.getLong("affected_agents"),
                        rs.getLong("inspected")));
    }

    @Override
    public long totalAgents() {
        Long total = jdbcTemplate.queryForObject(TOTAL_AGENTS_SQL, Long.class);
        return total == null ? 0 : total;
    }
}
