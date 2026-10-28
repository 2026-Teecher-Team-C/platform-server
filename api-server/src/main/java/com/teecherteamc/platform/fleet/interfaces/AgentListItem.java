package com.teecherteamc.platform.fleet.interfaces;

import com.teecherteamc.platform.fleet.domain.AgentListRow;
import java.time.Instant;
import java.util.UUID;

/**
 * 에이전트 목록 응답 한 줄. GET /api/admin/agents의 content 안에 들어간다.
 *
 * 토큰 해시는 이 응답에 없다. AgentListRow가 처음부터 읽지 않는다.
 * lastHeartbeatAt: 하트비트가 한 번도 안 온 에이전트는 null이다.
 */
public record AgentListItem(
        UUID agentId,
        String hostname,
        String osPlatform,
        String agentVersion,
        String status,
        Instant lastHeartbeatAt) {

    /** 조회한 한 줄(AgentListRow)을 응답 모양으로 바꾼다. 필드 이름이 같아서 그대로 옮긴다. */
    public static AgentListItem from(AgentListRow row) {
        return new AgentListItem(
                row.agentId(),
                row.hostname(),
                row.osPlatform(),
                row.agentVersion(),
                row.status(),
                row.lastHeartbeatAt());
    }
}