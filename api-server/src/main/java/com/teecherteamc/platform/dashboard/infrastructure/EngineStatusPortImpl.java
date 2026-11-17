package com.teecherteamc.platform.dashboard.infrastructure;

import com.teecherteamc.platform.dashboard.domain.EngineStatusPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** analyses의 최근 기록은 idx_analyses_analyzed_at(V16)으로 최신부터 읽는다. */
@Component
class EngineStatusPortImpl implements EngineStatusPort {

    private static final String SUCCESS = "status = 'SUCCESS'";
    private static final String FAILURE = "status IN ('TIMEOUT', 'CRASH', 'OOM')";

    private final JdbcTemplate jdbcTemplate;

    EngineStatusPortImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public EngineActivity latestActivity(Instant now) {
        return new EngineActivity(latest(SUCCESS, now), latest(FAILURE, now));
    }

    private Instant latest(String statusCondition, Instant now) {
        return first("SELECT analyzed_at FROM analyses WHERE " + statusCondition
                + " AND analyzed_at <= ? ORDER BY analyzed_at DESC LIMIT 1", Timestamp.from(now));
    }

    @Override
    public Instant firstFailureAfter(Instant after, Instant now) {
        if (after == null) {
            return first("SELECT analyzed_at FROM analyses WHERE " + FAILURE
                    + " AND analyzed_at <= ? ORDER BY analyzed_at ASC LIMIT 1", Timestamp.from(now));
        }
        return first("SELECT analyzed_at FROM analyses WHERE " + FAILURE
                        + " AND analyzed_at > ? AND analyzed_at <= ? ORDER BY analyzed_at ASC LIMIT 1",
                Timestamp.from(after), Timestamp.from(now));
    }

    @Override
    public FailCloseImpact failClosesBetween(Instant since, Instant now) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) AS fail_closes, COUNT(DISTINCT e.agent_id) AS affected_agents
                FROM download_events e
                JOIN agents a ON a.agent_id = e.agent_id
                JOIN devices d ON d.device_id = a.device_id
                WHERE e.decision = 'FAIL_CLOSE' AND e.created_at >= ? AND e.created_at <= ?
                """,
                (rs, rowNum) -> new FailCloseImpact(rs.getLong("fail_closes"), rs.getLong("affected_agents")),
                Timestamp.from(since), Timestamp.from(now));
    }

    private Instant first(String sql, Object... args) {
        List<Instant> rows = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getTimestamp(1).toInstant(), args);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
