package com.teecherteamc.platform.event.infrastructure;

import com.teecherteamc.platform.event.domain.DownloadEventListFilter;
import com.teecherteamc.platform.event.domain.DownloadEventListRow;
import com.teecherteamc.platform.event.domain.ListDownloadEventsPort;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import com.teecherteamc.platform.common.DashboardReadJdbc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * download_events를 agents/devices와 조인해 hostname까지 한 번에 읽는 어댑터. fleet 컨텍스트의
 * JPA 애그리거트(Agent/Device)는 안 거친다 — 목록 1건당 hostname 하나 보여주려고 애그리거트 전체를
 * 로드할 이유가 없다.
 *
 * <p>심각도·대표 룰·확인 여부는 download_event_threats 뷰(V7·V12)에서 읽는다. 뷰는 판정과 상관없이 sha256의 룰을
 * 붙이므로, 콘솔 계약(차단이 아니면 비움)에 맞게 여기서 BLOCKED일 때만 내보낸다.
 */
@Component
class ListDownloadEventsPortImpl implements ListDownloadEventsPort {

    private static final String SEVERITY = "CASE WHEN e.decision = 'BLOCKED' THEN t.severity END";
    private static final String SEVERITY_RANK = "CASE WHEN e.decision = 'BLOCKED' THEN t.severity_rank END";
    // 판정 전(decision NULL) 행에서 NULL이 나오면 NOT 조건에서도 빠지므로 false로 맞춘다.
    private static final String ACKNOWLEDGED =
            "(COALESCE(e.decision IN ('BLOCKED', 'FAIL_CLOSE'), false) AND t.acknowledged)";

    private static final String FROM_FOR_COUNT =
            """
            FROM download_events e
            JOIN agents a ON a.agent_id = e.agent_id
            JOIN devices d ON d.device_id = a.device_id
            """;

    private static final String FROM =
            FROM_FOR_COUNT + "JOIN download_event_threats t ON t.event_id = e.event_id\n";

    private static final String SELECT =
            """
            SELECT e.event_id, e.agent_id, d.hostname, e.sha256, e.filename, e.mime_type,
                   e.file_size, e.decision, e.decision_source, e.cache_hit, e.hold_duration_ms,
                   e.created_at, s.classification,
            """
                    + SEVERITY + " AS severity, "
                    + "CASE WHEN e.decision = 'BLOCKED' THEN t.top_rule_name END AS top_rule_name, "
                    + "CASE WHEN e.decision = 'BLOCKED' THEN t.extra_rule_count END AS extra_rule_count, "
                    + ACKNOWLEDGED + " AS acknowledged "
                    + FROM
                    + " LEFT JOIN similarity_assessments s ON s.assessment_id = e.similarity_assessment_id"
                    + " WHERE 1 = 1";

    private final JdbcTemplate jdbcTemplate;

    // 문장 타임아웃(10초)이 걸린 대시보드 읽기 전용 템플릿(DashboardReadJdbc).
    ListDownloadEventsPortImpl(DashboardReadJdbc readJdbc) {
        this.jdbcTemplate = readJdbc.jdbc();
    }

    @Override
    public List<DownloadEventListRow> list(DownloadEventListFilter filter, long offset, int limit) {
        StringBuilder sql = new StringBuilder(SELECT);
        List<Object> params = new ArrayList<>();
        appendFilters(sql, params, filter);
        sql.append(" ORDER BY e.created_at DESC, e.event_id DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);
        return jdbcTemplate.query(
                sql.toString(),
                (rs, rowNum) -> new DownloadEventListRow(
                        UUID.fromString(rs.getString("event_id")),
                        UUID.fromString(rs.getString("agent_id")),
                        rs.getString("hostname"),
                        rs.getString("sha256"),
                        rs.getString("filename"),
                        rs.getString("mime_type"),
                        rs.getObject("file_size", Long.class),
                        rs.getString("decision"),
                        rs.getString("decision_source"),
                        // cache_hit은 nullable이지만 getBoolean()은 SQL NULL을 false로 돌려준다 —
                        // 콘솔 타입(cacheHit: boolean, non-null)과 맞아서 의도적으로 그대로 둔다.
                        rs.getBoolean("cache_hit"),
                        rs.getObject("hold_duration_ms", Integer.class),
                        rs.getTimestamp("created_at").toInstant(),
                        // 이벤트 FK의 분류만 요약한다. 없는 평가는 NONE으로 바꾸지 않는다.
                        rs.getString("classification"),
                        rs.getString("severity"),
                        rs.getString("top_rule_name"),
                        // 룰이 없는 행은 NULL이고 getInt()가 0으로 돌려준다(콘솔 extraRuleCount: number).
                        rs.getInt("extra_rule_count"),
                        rs.getBoolean("acknowledged")),
                params.toArray());
    }

    @Override
    public long count(DownloadEventListFilter filter) {
        // list()와 같은 agents/devices 조인을 거친다. minSeverityRank·acknowledged 필터가 없으면
        // download_event_threats(1:1 LEFT JOIN)를 생략해도 건수가 같고 LATERAL 비용을 아낀다.
        boolean needsThreats = filter.minSeverityRank() != null || filter.acknowledged() != null;
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) " + (needsThreats ? FROM : FROM_FOR_COUNT) + " WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        appendFilters(sql, params, filter);
        Long total = jdbcTemplate.queryForObject(sql.toString(), Long.class, params.toArray());
        return total == null ? 0 : total;
    }

    private static void appendFilters(StringBuilder sql, List<Object> params, DownloadEventListFilter filter) {
        if (filter.agentId() != null) {
            sql.append(" AND e.agent_id = ?");
            params.add(filter.agentId());
        }
        if (!filter.decisions().isEmpty()) {
            sql.append(" AND e.decision IN (")
                    .append(String.join(", ", Collections.nCopies(filter.decisions().size(), "?")))
                    .append(")");
            params.addAll(filter.decisions());
        }
        if (filter.from() != null) {
            sql.append(" AND e.created_at >= ?");
            params.add(Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            sql.append(" AND e.created_at < ?");
            params.add(Timestamp.from(filter.to()));
        }
        if (filter.minSeverityRank() != null) {
            sql.append(" AND ").append(SEVERITY_RANK).append(" >= ?");
            params.add(filter.minSeverityRank());
        }
        if (filter.acknowledged() != null) {
            sql.append(filter.acknowledged() ? " AND " : " AND NOT ").append(ACKNOWLEDGED);
        }
    }
}
