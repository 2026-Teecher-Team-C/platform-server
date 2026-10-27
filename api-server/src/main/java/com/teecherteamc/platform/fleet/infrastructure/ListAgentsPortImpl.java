package com.teecherteamc.platform.fleet.infrastructure;

import com.teecherteamc.platform.fleet.domain.AgentListRow;
import com.teecherteamc.platform.fleet.domain.ListAgentsPort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 에이전트 목록을 agents JOIN devices로 읽는다. (읽기 전용)
 *
 * 토큰 해시(agent_token_hash, enrollment_token_hash)는 SELECT에 넣지 않는다.
 * 읽지 않으니 응답에 실수로 나갈 일이 없다.
 */
@Component
class ListAgentsPortImpl implements ListAgentsPort {

    // WHERE 1 = 1: 뒤에 조건을 AND로 이어 붙이기 쉽게 하려는 관용구
    private static final String BASE_SQL = """
            SELECT a.agent_id, d.hostname, d.os_platform, a.agent_version, a.status, a.last_heartbeat_at
            FROM agents a
            JOIN devices d ON d.device_id = a.device_id
            WHERE 1 = 1
            """;

    // hostname, os_platform 필터가 devices 컬럼이라서 개수를 셀 때도 조인이 필요하다.
    private static final String COUNT_SQL = """
            SELECT count(*)
            FROM agents a
            JOIN devices d ON d.device_id = a.device_id
            WHERE 1 = 1
            """;

    private final JdbcTemplate jdbcTemplate;

    ListAgentsPortImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<AgentListRow> list(String status, String hostname, String osPlatform, long offset, int limit) {
        StringBuilder sql = new StringBuilder(BASE_SQL);
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, status, hostname, osPlatform);

        // 최근 하트비트가 위. NULLS LAST: 하트비트가 한 번도 안 온 에이전트는 맨 뒤로 보낸다.
        // (PostgreSQL은 DESC일 때 NULL을 맨 앞에 놓는다.)
        // agent_id: 하트비트 시각이 같은 행끼리도 순서가 고정되게 해서 페이지를 넘겨도 순서가 흔들리지 않게 한다.
        sql.append(" ORDER BY a.last_heartbeat_at DESC NULLS LAST, a.agent_id LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> toRow(rs), args.toArray());
    }

    @Override
    public long count(String status, String hostname, String osPlatform) {
        StringBuilder sql = new StringBuilder(COUNT_SQL);
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, status, hostname, osPlatform);

        Long total = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    /**
     * null이 아닌 필터만 WHERE에 붙인다. list와 count가 이 메서드를 같이 써서 조건이 어긋나지 않는다.
     * 값은 문자열로 이어 붙이지 않고 ?로 넘긴다. (SQL 주입 방지)
     */
    private static void appendFilters(StringBuilder sql, List<Object> args,
                                      String status, String hostname, String osPlatform) {
        if (status != null) {
            sql.append(" AND a.status = ?");
            args.add(status);
        }
        if (osPlatform != null) {
            sql.append(" AND d.os_platform = ?");
            args.add(osPlatform);
        }
        if (hostname != null) {
            // 부분 일치, 대소문자 무시. LIKE 대신 strpos를 쓰면 %, _ 같은 문자를 따로 처리하지 않아도 된다.
            sql.append(" AND strpos(lower(d.hostname), lower(?)) > 0");
            args.add(hostname);
        }
    }

    private static AgentListRow toRow(ResultSet rs) throws SQLException {
        Timestamp heartbeat = rs.getTimestamp("last_heartbeat_at");   // 등록 직후에는 NULL
        return new AgentListRow(
                rs.getObject("agent_id", UUID.class),
                rs.getString("hostname"),
                rs.getString("os_platform"),
                rs.getString("agent_version"),
                rs.getString("status"),
                heartbeat == null ? null : heartbeat.toInstant());
    }
}