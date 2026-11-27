package com.teecherteamc.platform.dashboard.infrastructure;

import com.teecherteamc.platform.dashboard.domain.UnacknowledgedBlockRow;
import com.teecherteamc.platform.dashboard.domain.UnacknowledgedBlocksPort;
import java.util.List;
import java.util.UUID;
import com.teecherteamc.platform.common.DashboardReadJdbc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * download_event_threats 뷰(V7)로 심각도·대표 룰·확인 여부를 읽는다. 차단 판단과 심각도 계산은 뷰에만 두고
 * 여기서는 거르기만 한다. 목록과 개수는 ListDownloadEventsPortImpl과 같은 agents/devices 조인을 거쳐,
 * 이벤트 목록에 안 보이는 행이 배지 숫자에 섞이지 않게 한다.
 *
 * <p>FAIL_CLOSE는 뷰에서 심각도가 NULL이라 severity_rank 조건에서 자연히 빠진다.
 */
@Component
class UnacknowledgedBlocksPortImpl implements UnacknowledgedBlocksPort {

    // severity_rank >= 3만 필요하므로 FAIL_CLOSE(NULL)·SIMILARITY(2)는 처음부터 제외한다. 후보는 V25 커버링 인덱스(idx_events_blocked_covering)에서 힙 없이 읽는다.
    private static final String CANDIDATE_FROM =
            """
            FROM download_events e
            JOIN agents a ON a.agent_id = e.agent_id
            JOIN devices d ON d.device_id = a.device_id
            """;

    private static final String CANDIDATE_FILTER =
            """
            WHERE e.decision = 'BLOCKED'
              AND e.decision_source IN ('ENGINE', 'CACHE', 'BLACKLIST')
              AND NOT EXISTS (
                  SELECT 1 FROM download_event_acknowledgements ack WHERE ack.event_id = e.event_id
              )
            """;

    private static final String LIST_SQL =
            """
            SELECT e.event_id, e.created_at, e.agent_id, d.hostname, e.filename,
                   e.decision_source AS reason_type,
                   CASE WHEN e.decision_source IN ('ENGINE', 'CACHE') THEN tm.top_rule_name ELSE NULL END AS top_rule_name,
                   CASE WHEN e.decision_source IN ('ENGINE', 'CACHE') AND tm.match_count IS NOT NULL
                        THEN tm.match_count - 1 ELSE NULL END AS extra_rule_count,
                   CASE WHEN e.decision_source = 'BLACKLIST' THEN bl.severity
                        WHEN e.decision_source IN ('ENGINE', 'CACHE') THEN tm.top_severity ELSE NULL END AS severity
            """
            + CANDIDATE_FROM
            + """
            LEFT JOIN LATERAL (
                SELECT am.rule_name AS top_rule_name,
                       am.severity AS top_severity,
                       CASE am.severity WHEN 'CRITICAL' THEN 4 WHEN 'HIGH' THEN 3 WHEN 'MEDIUM' THEN 2 WHEN 'LOW' THEN 1 ELSE 0 END AS top_severity_rank,
                       COUNT(*) OVER () AS match_count
                FROM (
                    SELECT an.analysis_id
                    FROM analyses an
                    WHERE an.sha256 = e.sha256 AND an.status = 'SUCCESS'
                    ORDER BY an.analyzed_at DESC
                    LIMIT 1
                ) lsa
                JOIN analysis_matches am ON am.analysis_id = lsa.analysis_id
                WHERE e.decision_source IN ('ENGINE', 'CACHE')
                ORDER BY 3 DESC, am.rule_name ASC
                LIMIT 1
            ) tm ON true
            LEFT JOIN hash_blacklist bl
              ON e.decision_source = 'BLACKLIST' AND bl.hash_type = 'SHA256' AND bl.hash_value = e.sha256
            """
            + CANDIDATE_FILTER
            + """
              AND (
                  (e.decision_source = 'BLACKLIST' AND bl.severity IN ('HIGH', 'CRITICAL'))
                  OR (e.decision_source IN ('ENGINE', 'CACHE') AND tm.top_severity_rank >= 3)
              )
            ORDER BY e.created_at DESC, e.event_id DESC
            LIMIT ? OFFSET ?
            """;

    private static final String COUNT_SQL =
            """
            WITH grouped AS (
                SELECT e.sha256, e.decision_source, COUNT(*) AS cnt
            """
            + CANDIDATE_FROM
            + CANDIDATE_FILTER
            + """
                GROUP BY e.sha256, e.decision_source
            )
            SELECT COALESCE(SUM(g.cnt), 0)
            FROM grouped g
            LEFT JOIN LATERAL (
                SELECT am.severity
                FROM (
                    SELECT an.analysis_id
                    FROM analyses an
                    WHERE an.sha256 = g.sha256 AND an.status = 'SUCCESS'
                    ORDER BY an.analyzed_at DESC
                    LIMIT 1
                ) lsa
                JOIN analysis_matches am ON am.analysis_id = lsa.analysis_id
                WHERE g.decision_source IN ('ENGINE', 'CACHE')
                ORDER BY (CASE am.severity WHEN 'CRITICAL' THEN 4 WHEN 'HIGH' THEN 3 WHEN 'MEDIUM' THEN 2 WHEN 'LOW' THEN 1 ELSE 0 END) DESC,
                         am.rule_name ASC
                LIMIT 1
            ) tm ON true
            -- 블랙리스트 심각도는 고유 해시마다 PK로 한 건씩 찾는다(스칼라 서브쿼리). 조인으로 쓰면 플래너가
            -- 117만 행 해시를 만들다 디스크로 넘쳤다(PC 3천 대, temp 100MB, 2026-11-28). LATERAL로 써도 조인으로
            -- 풀려서 같았다. PK (hash_type, hash_value)라 결과는 같다.
            WHERE (g.decision_source = 'BLACKLIST'
                   AND (SELECT bl.severity FROM hash_blacklist bl
                        WHERE bl.hash_type = 'SHA256' AND bl.hash_value = g.sha256) IN ('HIGH', 'CRITICAL'))
               OR (g.decision_source IN ('ENGINE', 'CACHE') AND tm.severity IN ('HIGH', 'CRITICAL'))
            """;

    private final JdbcTemplate jdbcTemplate;

    // 문장 타임아웃(10초)이 걸린 대시보드 읽기 전용 템플릿(DashboardReadJdbc).
    UnacknowledgedBlocksPortImpl(DashboardReadJdbc readJdbc) {
        this.jdbcTemplate = readJdbc.jdbc();
    }

    @Override
    public List<UnacknowledgedBlockRow> list(long offset, int limit) {
        return jdbcTemplate.query(
                LIST_SQL,
                (rs, rowNum) -> new UnacknowledgedBlockRow(
                        UUID.fromString(rs.getString("event_id")),
                        rs.getTimestamp("created_at").toInstant(),
                        UUID.fromString(rs.getString("agent_id")),
                        rs.getString("hostname"),
                        rs.getString("filename"),
                        // 뷰는 ENGINE·CACHE·BLACKLIST로 나누지만 콘솔은 "룰로 막힘 / 블랙리스트로 막힘" 둘만 구분한다.
                        "BLACKLIST".equals(rs.getString("reason_type")) ? "BLACKLIST" : "RULE",
                        rs.getString("top_rule_name"),
                        // 뷰는 룰 개념이 없는 행(BLACKLIST)에서 NULL이다. getInt()가 NULL을 0으로 돌려준다.
                        rs.getInt("extra_rule_count"),
                        rs.getString("severity")),
                limit, offset);
    }

    @Override
    public long count() {
        Long total = jdbcTemplate.queryForObject(COUNT_SQL, Long.class);
        return total == null ? 0 : total;
    }
}
