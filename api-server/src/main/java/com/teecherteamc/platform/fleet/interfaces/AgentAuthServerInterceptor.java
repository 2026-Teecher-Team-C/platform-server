package com.teecherteamc.platform.fleet.interfaces;

import com.teecherteamc.platform.fleet.domain.Agent;
import com.teecherteamc.platform.fleet.domain.AgentRepository;
import com.teecherteamc.platform.fleet.domain.TokenHash;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AgentGrpcService에만 스코프를 건다({@code @GrpcService(interceptors = ...)}) — 전역 인터셉터로
 * 만들면 Bearer 토큰을 안 보내는 기존 VerdictService 테스트가 깨진다. RegisterAgent는 요청 바디의
 * 1회용 등록 토큰으로 자체 인증하므로 여기서 건너뛴다. 실패 사유(헤더 없음/형식 오류/토큰 불일치/
 * 만료/REVOKED)는 전부 같은 UNAUTHENTICATED로 응답한다 — 어떤 사유인지 클라이언트에 알려주지 않는
 * 기존 컨벤션(withCause 미노출)과 같은 이유다. 서버 로그에만 구체적인 사유를 남긴다.
 */
@Component
class AgentAuthServerInterceptor implements ServerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AgentAuthServerInterceptor.class);
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final String BEARER_PREFIX = "Bearer ";

    private final AgentRepository agentRepository;

    AgentAuthServerInterceptor(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        if (call.getMethodDescriptor().getFullMethodName().endsWith("/RegisterAgent")) {
            return next.startCall(call, headers);
        }

        Optional<Agent> authenticated = authenticate(headers.get(AUTHORIZATION));
        if (authenticated.isEmpty()) {
            call.close(Status.UNAUTHENTICATED.withDescription("인증 실패"), new Metadata());
            return new ServerCall.Listener<>() {};
        }

        Context context = Context.current().withValue(AgentAuthContext.AGENT_ID, authenticated.get().getAgentId());
        return Contexts.interceptCall(context, call, headers, next);
    }

    private Optional<Agent> authenticate(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            log.warn("agent auth: authorization 헤더 없음 또는 형식 오류");
            return Optional.empty();
        }
        String token = authorizationHeader.substring(BEARER_PREFIX.length());
        String tokenHash = TokenHash.sha256Hex(token);

        Optional<Agent> agent = agentRepository.findByAgentTokenHash(tokenHash)
                .filter(a -> a.getAgentTokenHash() != null && TokenHash.matches(a.getAgentTokenHash(), tokenHash));
        if (agent.isEmpty()) {
            log.warn("agent auth: agent_token과 일치하는 에이전트 없음");
            return Optional.empty();
        }
        Agent found = agent.get();
        if ("REVOKED".equals(found.getStatus())) {
            log.warn("agent auth: agent_id={} 는 REVOKED 상태", found.getAgentId());
            return Optional.empty();
        }
        if (found.getAgentTokenExpiresAt() == null || Instant.now().isAfter(found.getAgentTokenExpiresAt())) {
            log.warn("agent auth: agent_id={} 의 agent_token이 만료됨", found.getAgentId());
            return Optional.empty();
        }
        return Optional.of(found);
    }
}
