package com.teecherteamc.platform.hashlist.infrastructure;

import com.teecherteamc.platform.hashlist.domain.AffectedDeviceRow;
import com.teecherteamc.platform.hashlist.domain.FindAffectedDevicesPort;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 해시를 실제로 받은 기기를 download_events에서 직접 읽는다. (읽기 전용)
 *
 * decision IN (RELEASED, BYPASSED)만 본다 — BLOCKED/FAIL_CLOSE는 애초에 막혀서 기기가 못 받았다.
 */
@Component
class FindAffectedDevicesPortImpl implements FindAffectedDevicesPort {

    private static final String SQL = """
            SELECT d.device_id, d.hostname, count(*) AS received_count, max(e.created_at) AS last_received_at
            FROM download_events e
            JOIN agents a ON a.agent_id = e.agent_id
            JOIN devices d ON d.device_id = a.device_id
            WHERE e.sha256 = ? AND e.decision IN ('RELEASED', 'BYPASSED')
            GROUP BY d.device_id, d.hostname
            ORDER BY last_received_at DESC
            """;

    private final JdbcTemplate jdbcTemplate;

    FindAffectedDevicesPortImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<AffectedDeviceRow> findBySha256(String sha256) {
        return jdbcTemplate.query(
                SQL,
                (rs, rowNum) -> new AffectedDeviceRow(
                        UUID.fromString(rs.getString("device_id")),
                        rs.getString("hostname"),
                        rs.getInt("received_count"),
                        rs.getTimestamp("last_received_at").toInstant()),
                sha256);
    }
}
