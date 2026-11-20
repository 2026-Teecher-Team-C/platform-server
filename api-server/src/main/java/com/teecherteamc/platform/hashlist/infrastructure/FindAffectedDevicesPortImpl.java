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
 * decision = RELEASED만 본다 — BLOCKED/FAIL_CLOSE는 애초에 막혀서 기기가 못 받았다.
 *
 * <p>BYPASSED(바이패스 도메인)는 일부러 뺐다. 바이패스는 검사를 통째로 생략하는 경로라
 * {@code ReportEventUseCase.resolveSafeSha256()}이 sha256을 거의 항상 NULL로 저장하고(해시를
 * 아예 안 재니까), 그 전에 서버가 "뭘 받았는지"조차 모른다. 그래서 "통과시켰던 파일이 나중에
 * 악성으로 밝혀짐" 시나리오 자체가 바이패스에는 성립하지 않는다 — 바이패스로 해시를 남기게
 * 바꾸는 건 "검사 생략"이라는 설계 의도와 부딪혀서 하지 않기로 했다(이슈 #111).
 */
@Component
class FindAffectedDevicesPortImpl implements FindAffectedDevicesPort {

    private static final String SQL = """
            SELECT d.device_id, d.hostname, count(*) AS received_count, max(e.created_at) AS last_received_at
            FROM download_events e
            JOIN agents a ON a.agent_id = e.agent_id
            JOIN devices d ON d.device_id = a.device_id
            WHERE e.sha256 = ? AND e.decision = 'RELEASED'
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
