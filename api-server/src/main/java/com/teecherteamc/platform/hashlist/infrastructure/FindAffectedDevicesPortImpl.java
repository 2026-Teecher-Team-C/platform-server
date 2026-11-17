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
 *
 * <p>알려진 범위 제한: 바이패스(BYPASSED) 다운로드는 애초에 해시를 계산하지 않는 경로라,
 * {@code ReportEventUseCase.resolveSafeSha256()}이 (TLSH 근거가 연결된 드문 경우가 아니면)
 * sha256을 NULL로 저장한다. 그래서 이 조회는 실질적으로 RELEASED 건 위주로만 동작하고, 순수
 * 바이패스로 받은 기기는 거의 못 찾는다 — 바이패스 다운로드에 해시를 남기는 건 "검사 생략"이라는
 * 설계 의도와 부딪히는 별도 논의거리라 이번 범위에서 고치지 않는다.
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
