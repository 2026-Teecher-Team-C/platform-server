package com.teecherteamc.platform.fleet.interfaces;

import io.grpc.Context;
import java.util.UUID;

/**
 * AgentAuthServerInterceptor가 Bearer 토큰에서 유도한 agent_id를 넣어두는 gRPC Context 키.
 * VerdictGrpcService도 이 키로 SubmitFile과 ReportEvent의 동일 agent 소유권을 검증한다.
 */
public final class AgentAuthContext {

    public static final Context.Key<UUID> AGENT_ID = Context.key("agentId");

    private AgentAuthContext() {}
}
