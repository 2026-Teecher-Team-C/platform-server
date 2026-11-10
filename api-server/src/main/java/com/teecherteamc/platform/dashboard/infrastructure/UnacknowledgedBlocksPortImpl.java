package com.teecherteamc.platform.dashboard.infrastructure;

import com.teecherteamc.platform.dashboard.domain.UnacknowledgedBlockRow;
import com.teecherteamc.platform.dashboard.domain.UnacknowledgedBlocksPort;
import java.util.List;
import java.util.UUID;
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

    private static final String FROM_WHERE =
            """
            FROM download_events e
            JOIN download_event_threats t ON t.event_id = e.event_id
            JOIN agents a ON a.agent_id = e.agent_id
            JOIN devices d ON d.device_id = a.device_id
            WHERE e.decision IN ('BLOCKED', 'FAIL_CLOSE')
              AND t.severity_rank >= 3
              AND NOT t.acknowledged
            """;

    private final JdbcTemplate jdbcTemplate;

    UnacknowledgedBlocksPortImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<UnacknowledgedBlockRow> list(long offset, int limit) {
        return jdbcTemplate.query(
                "SELECT e.event_id, e.created_at, e.agent_id, d.hostname, e.filename, t.reason_type, "
                        + "t.top_rule_name, t.extra_rule_count, t.severity "
                        + FROM_WHERE
                        + " ORDER BY e.created_at DESC, e.event_id DESC LIMIT ? OFFSET ?",
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
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) " + FROM_WHERE, Long.class);
        return total == null ? 0 : total;
    }
}
