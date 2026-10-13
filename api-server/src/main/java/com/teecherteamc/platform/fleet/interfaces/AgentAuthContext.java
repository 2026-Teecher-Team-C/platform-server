package com.teecherteamc.platform.fleet.interfaces;

import io.grpc.Context;
import java.util.UUID;

/**
 * AgentAuthServerInterceptor가 Bearer 토큰에서 유도한 agent_id를 넣어두는 gRPC Context 키.
 * public인 이유: VerdictGrpcService가 PlaceholderAgent.DEV_AGENT_ID를 걷어낼 때 이 키를
 * 가져다 쓰게 하려는 것 — 그 교체 자체는 이번 작업 범위 밖이다.
 */
public final class AgentAuthContext {

    public static final Context.Key<UUID> AGENT_ID = Context.key("agentId");

    private AgentAuthContext() {}
}
