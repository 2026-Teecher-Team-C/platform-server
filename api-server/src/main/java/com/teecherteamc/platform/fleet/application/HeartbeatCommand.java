package com.teecherteamc.platform.fleet.application;

import java.util.UUID;

/** agentId는 요청 바디가 아니라 gRPC Context(Bearer 토큰에서 인터셉터가 유도)에서 온다. */
public record HeartbeatCommand(UUID agentId, String agentVersion) {}
