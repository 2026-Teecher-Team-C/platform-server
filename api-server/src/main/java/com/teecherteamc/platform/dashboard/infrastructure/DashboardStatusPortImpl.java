package com.teecherteamc.platform.dashboard.infrastructure;

import com.teecherteamc.platform.dashboard.domain.BlockCounts;
import com.teecherteamc.platform.dashboard.domain.DashboardPeriod;
import com.teecherteamc.platform.dashboard.domain.DashboardStatusPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 카운터와 엔진 상태 SQL. 이벤트 집계는 DashboardWidgetsPortImpl과 같은 agents/devices 조인을 거친다. */
@Component
class DashboardStatusPortImpl implements DashboardStatusPort {

    private static final String EVENTS_IN_PERIOD =
            """
            FROM download_events e
            JOIN agents a ON a.agent_id = e.agent_id
            JOIN devices d ON d.device_id = a.device_id
            WHERE e.created_at >= ? AND e.created_at < ?
            """;

    private static final String FAILURE = "status IN ('TIMEOUT', 'CRASH', 'OOM')";

    private final JdbcTemplate jdbcTemplate;

    DashboardStatusPortImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public BlockCounts counts(DashboardPeriod period) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FILTER (WHERE e.decision IN ('BLOCKED', 'FAIL_CLOSE')) AS blocked,
                       COUNT(*) FILTER (WHERE e.decision = 'BLOCKED' AND e.decision_source = 'ENGINE') AS engine,
                       COUNT(*) FILTER (WHERE e.decision = 'BLOCKED' AND e.decision_source = 'CACHE') AS cache,
                       COUNT(*) FILTER (WHERE e.decision = 'BLOCKED' AND e.decision_source = 'BLACKLIST')
                           AS blacklist,
                       COUNT(*) FILTER (WHERE e.decision = 'FAIL_CLOSE') AS fail_close,
                       COUNT(DISTINCT e.agent_id) FILTER (WHERE e.decision IN ('BLOCKED', 'FAIL_CLOSE'))
                           AS affected_agents,
                       COUNT(*) FILTER (WHERE e.decision IS NOT NULL AND e.decision <> 'BYPASSED') AS inspected
                """ + EVENTS_IN_PERIOD,
                (rs, rowNum) -> new BlockCounts(
                        rs.getLong("blocked"),
                        rs.getLong("engine"),
                        rs.getLong("cache"),
                        rs.getLong("blacklist"),
                        rs.getLong("fail_close"),
                        rs.getLong("affected_agents"),
                        rs.getLong("inspected")),
                Timestamp.from(period.from()), Timestamp.from(period.to()));
    }

    @Override
    public long blockedCount(DashboardPeriod period) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) " + EVENTS_IN_PERIOD + " AND e.decision IN ('BLOCKED', 'FAIL_CLOSE')",
                Long.class, Timestamp.from(period.from()), Timestamp.from(period.to()));
        return count == null ? 0 : count;
    }

    @Override
    public long activeAgents() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agents WHERE status <> 'REVOKED'", Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public Optional<AnalysisMark> latestAnalysis(Instant now) {
        List<AnalysisMark> rows = jdbcTemplate.query(
                """
                SELECT status, analyzed_at FROM analyses
                WHERE status <> 'UNSUPPORTED' AND analyzed_at <= ?
                ORDER BY analyzed_at DESC LIMIT 1
                """,
                (rs, rowNum) -> new AnalysisMark(rs.getString("status"), rs.getTimestamp("analyzed_at").toInstant()),
                Timestamp.from(now));
        return rows.stream().findFirst();
    }

    @Override
    public Optional<Instant> lastSuccessAt(Instant now) {
        return instant("SELECT MAX(analyzed_at) FROM analyses WHERE status = 'SUCCESS' AND analyzed_at <= ?",
                Timestamp.from(now));
    }

    @Override
    public Optional<Instant> lastFailureAt(Instant now) {
        return instant("SELECT MAX(analyzed_at) FROM analyses WHERE " + FAILURE + " AND analyzed_at <= ?",
                Timestamp.from(now));
    }

    @Override
    public Optional<Instant> firstFailureAfter(Instant after, Instant now) {
        if (after == null) {
            return instant("SELECT MIN(analyzed_at) FROM analyses WHERE " + FAILURE + " AND analyzed_at <= ?",
                    Timestamp.from(now));
        }
        return instant(
                "SELECT MIN(analyzed_at) FROM analyses WHERE " + FAILURE + " AND analyzed_at > ? AND analyzed_at <= ?",
                Timestamp.from(after), Timestamp.from(now));
    }

    @Override
    public FailCloseImpact failClosesBetween(Instant since, Instant now) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) AS events, COUNT(DISTINCT e.agent_id) AS agents
                FROM download_events e
                JOIN agents a ON a.agent_id = e.agent_id
                JOIN devices d ON d.device_id = a.device_id
                WHERE e.decision = 'FAIL_CLOSE' AND e.created_at >= ? AND e.created_at <= ?
                """,
                (rs, rowNum) -> new FailCloseImpact(rs.getLong("events"), rs.getLong("agents")),
                Timestamp.from(since), Timestamp.from(now));
    }

    private Optional<Instant> instant(String sql, Object... params) {
        Timestamp value = jdbcTemplate.queryForObject(sql, Timestamp.class, params);
        return Optional.ofNullable(value).map(Timestamp::toInstant);
    }
}
