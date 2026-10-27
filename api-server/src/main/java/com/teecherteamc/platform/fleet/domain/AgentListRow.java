package com.teecherteamc.platform.fleet.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 에이전트 목록 한 줄. agents와 devices를 조인해서 읽은 값이다. (읽기 전용)
 *
 * 토큰 해시(agent_token_hash, enrollment_token_hash)는 일부러 담지 않는다.
 * 읽지 않으면 응답에 실수로 나갈 일이 없다.
 *
 * lastHeartbeatAt: 하트비트가 한 번도 안 온 에이전트는 null이다.
 */
public record AgentListRow(
        UUID agentId,
        String hostname,        // devices.hostname
        String osPlatform,      // devices.os_platform (WINDOWS / MACOS / LINUX)
        String agentVersion,
        String status,          // ACTIVE / INACTIVE / REVOKED
        Instant lastHeartbeatAt) {
}