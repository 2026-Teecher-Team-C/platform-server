package com.teecherteamc.platform.fleet.application;

import java.time.Instant;
import java.util.UUID;

/** agentToken은 평문 — 이 레코드가 존재하는 짧은 시간 동안만 메모리에 있고, 저장되거나 로그에 남지 않는다. */
public record RegisterAgentResult(UUID agentId, String agentToken, Instant tokenExpiresAt) {}
