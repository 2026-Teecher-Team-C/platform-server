package com.teecherteamc.platform.dashboard.infrastructure;

import com.teecherteamc.platform.dashboard.domain.BlockEvent;
import com.teecherteamc.platform.dashboard.domain.DashboardPeriod;
import com.teecherteamc.platform.dashboard.domain.DashboardWidgetsPort;
import com.teecherteamc.platform.dashboard.domain.ThreatRow;
import com.teecherteamc.platform.dashboard.domain.TopSourceRow;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 위젯 집계 SQL. 심각도·대표 룰은 download_event_threats 뷰(V7·V12)와 같은 규칙을 쓴다.
 * 모든 쿼리는 ListDownloadEventsPortImpl과 같은 agents/devices 조인을 거친다.
 */
@Component
class DashboardWidgetsPortImpl implements DashboardWidgetsPort {

    private static final String EVENT_JOINS =
            """
            FROM download_events e
            JOIN agents a ON a.agent_id = e.agent_id
            JOIN devices d ON d.device_id = a.device_id
            """;

    private static final String BLOCKS_IN_PERIOD =
            " WHERE e.decision IN ('BLOCKED', 'FAIL_CLOSE') AND e.created_at >= ? AND e.created_at < ?";

    /**
     * 룰 줄: 같은 sha256의 최신 SUCCESS 분석에 걸린 룰 전부(뷰의 latest_success_analysis와 같다).
     * 블랙리스트 줄: hash_blacklist 심각도. TLSH 유사도 차단은 룰도 블랙리스트도 아니라 빠진다(콘솔 mock과 같다).
     */
    static final String THREATS_SQL =
            """
            WITH blocked AS (
                SELECT e.agent_id, e.sha256, e.decision_source, e.created_at
                FROM download_events e
                JOIN agents a ON a.agent_id = e.agent_id
                JOIN devices d ON d.device_id = a.device_id
                WHERE e.decision = 'BLOCKED'
                  AND e.created_at >= ? AND e.created_at < ?
            ),
            latest_success_analysis AS (
                SELECT DISTINCT ON (an.sha256) an.analysis_id, an.sha256
                FROM analyses an
                WHERE an.status = 'SUCCESS'
                  AND an.sha256 IN (SELECT sha256 FROM blocked WHERE decision_source IN ('ENGINE', 'CACHE'))
                ORDER BY an.sha256, an.analyzed_at DESC
            ),
            hits AS (
                SELECT 'RULE' AS kind, am.rule_name, am.severity, b.agent_id, b.created_at
                FROM blocked b
                JOIN latest_success_analysis l ON l.sha256 = b.sha256
                JOIN analysis_matches am ON am.analysis_id = l.analysis_id
                WHERE b.decision_source IN ('ENGINE', 'CACHE')
                UNION ALL
                SELECT 'BLACKLIST', NULL, bl.severity, b.agent_id, b.created_at
                FROM blocked b
                JOIN hash_blacklist bl ON bl.hash_type = 'SHA256' AND bl.hash_value = b.sha256::text
                WHERE b.decision_source = 'BLACKLIST'
            )
            SELECT kind, rule_name,
                   (ARRAY['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'])[MAX(
                       CASE severity WHEN 'CRITICAL' THEN 4 WHEN 'HIGH' THEN 3 WHEN 'MEDIUM' THEN 2 ELSE 1 END)]
                       AS severity,
                   COUNT(DISTINCT agent_id) AS affected_agents,
                   COUNT(*) AS block_count,
                   MIN(created_at) AS first_seen_at,
                   MAX(created_at) AS last_seen_at
            FROM hits
            GROUP BY kind, rule_name
            ORDER BY last_seen_at DESC, rule_name NULLS FIRST
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;

    DashboardWidgetsPortImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<ThreatRow> threats(DashboardPeriod period, int limit) {
        return jdbcTemplate.query(THREATS_SQL,
                (rs, rowNum) -> new ThreatRow(
                        rs.getString("kind"),
                        rs.getString("rule_name"),
                        rs.getString("severity"),
                        rs.getLong("affected_agents"),
                        rs.getLong("block_count"),
                        rs.getTimestamp("first_seen_at").toInstant(),
                        rs.getTimestamp("last_seen_at").toInstant()),
                Timestamp.from(period.from()), Timestamp.from(period.to()), limit);
    }

    @Override
    public List<BlockEvent> blocks(DashboardPeriod period) {
        // 대표 룰은 뷰에서 읽는다. 뷰는 판정과 무관하게 룰을 붙이므로 BLOCKED일 때만 쓴다.
        return jdbcTemplate.query(
                "SELECT e.created_at, e.decision, e.decision_source, "
                        + "CASE WHEN e.decision = 'BLOCKED' THEN t.top_rule_name END AS top_rule_name "
                        + EVENT_JOINS
                        + "JOIN download_event_threats t ON t.event_id = e.event_id"
                        + BLOCKS_IN_PERIOD,
                (rs, rowNum) -> new BlockEvent(
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getString("decision"),
                        rs.getString("decision_source"),
                        rs.getString("top_rule_name")),
                Timestamp.from(period.from()), Timestamp.from(period.to()));
    }

    @Override
    public List<TopSourceRow> topSources(DashboardPeriod period, SourceKind by, int limit) {
        String select = by == SourceKind.AGENT
                ? "SELECT e.agent_id::text AS key, d.hostname AS label, COUNT(*) AS block_count "
                : "SELECT e.request_host AS key, e.request_host AS label, COUNT(*) AS block_count ";
        String groupBy = by == SourceKind.AGENT ? " GROUP BY e.agent_id, d.hostname" : " GROUP BY e.request_host";
        String order = " ORDER BY block_count DESC, label ASC, key ASC LIMIT ?";
        return jdbcTemplate.query(
                select + EVENT_JOINS + BLOCKS_IN_PERIOD + groupBy + order,
                (rs, rowNum) -> new TopSourceRow(
                        rs.getString("key"), rs.getString("label"), rs.getLong("block_count")),
                Timestamp.from(period.from()), Timestamp.from(period.to()), limit);
    }
}
