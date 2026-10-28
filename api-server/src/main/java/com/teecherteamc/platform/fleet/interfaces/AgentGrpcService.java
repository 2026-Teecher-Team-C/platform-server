package com.teecherteamc.platform.fleet.interfaces;

import com.google.protobuf.Timestamp;
import com.teecherteamc.platform.fleet.application.HeartbeatCommand;
import com.teecherteamc.platform.fleet.application.HeartbeatUseCase;
import com.teecherteamc.platform.fleet.application.RegisterAgentCommand;
import com.teecherteamc.platform.fleet.application.RegisterAgentResult;
import com.teecherteamc.platform.fleet.application.RegisterAgentUseCase;
import com.teecherteamc.platform.fleet.domain.AgentNotFoundException;
import com.teecherteamc.platform.fleet.domain.AgentRevokedException;
import com.teecherteamc.platform.fleet.domain.EnrollmentTokenAlreadyRedeemedException;
import com.teecherteamc.platform.fleet.domain.EnrollmentTokenExpiredException;
import com.teecherteamc.platform.fleet.domain.InvalidEnrollmentTokenException;
import com.teecherteamc.platform.policy.application.GetPolicyUseCase;
import com.teecherteamc.platform.policy.domain.ActiveBypassHost;
import com.teecherteamc.platform.policy.domain.BypassCategory;
import com.teecherteamc.platform.proto.agent.v1.AgentServiceGrpc;
import com.teecherteamc.platform.proto.agent.v1.BypassHost;
import com.teecherteamc.platform.proto.agent.v1.GetPolicyRequest;
import com.teecherteamc.platform.proto.agent.v1.GetPolicyResponse;
import com.teecherteamc.platform.proto.agent.v1.HeartbeatRequest;
import com.teecherteamc.platform.proto.agent.v1.HeartbeatResponse;
import com.teecherteamc.platform.proto.agent.v1.OsPlatform;
import com.teecherteamc.platform.proto.agent.v1.RefreshAgentTokenRequest;
import com.teecherteamc.platform.proto.agent.v1.RefreshAgentTokenResponse;
import com.teecherteamc.platform.proto.agent.v1.RegisterAgentRequest;
import com.teecherteamc.platform.proto.agent.v1.RegisterAgentResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.service.GrpcService;

/**
 * AgentService의 gRPC 어댑터. RegisterAgent/Heartbeat/GetPolicy를 실제 구현한다 — RefreshAgentToken은
 * proto가 요구하는 메서드지만 이번 스프린트 범위 밖(S4에 팀과 재협의)이라 명시적으로 UNIMPLEMENTED를
 * 돌려준다. AgentAuthServerInterceptor를 이 서비스에만 스코프를 걸어서, Bearer 토큰이 필요 없는
 * RegisterAgent와 VerdictService의 기존 RPC들이 영향받지 않게 한다.
 */
@GrpcService(interceptors = AgentAuthServerInterceptor.class)
public class AgentGrpcService extends AgentServiceGrpc.AgentServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(AgentGrpcService.class);
    // devices.hostname/agents.agent_version/devices.hardware_uuid 컬럼 길이와 맞춘다 — 안 걸러내면
    // DB flush 시점에야 터져서 INVALID_ARGUMENT 대신 INTERNAL로 잘못 응답하게 된다.
    private static final int MAX_HOSTNAME_LENGTH = 255;
    private static final int MAX_AGENT_VERSION_LENGTH = 32;
    private static final int MAX_HARDWARE_UUID_LENGTH = 64;

    private final RegisterAgentUseCase registerAgentUseCase;
    private final HeartbeatUseCase heartbeatUseCase;
    private final GetPolicyUseCase getPolicyUseCase;

    public AgentGrpcService(
            RegisterAgentUseCase registerAgentUseCase,
            HeartbeatUseCase heartbeatUseCase,
            GetPolicyUseCase getPolicyUseCase) {
        this.registerAgentUseCase = registerAgentUseCase;
        this.heartbeatUseCase = heartbeatUseCase;
        this.getPolicyUseCase = getPolicyUseCase;
    }

    @Override
    public void registerAgent(RegisterAgentRequest request, StreamObserver<RegisterAgentResponse> responseObserver) {
        String validationError = validationError(request);
        if (validationError != null) {
            responseObserver.onError(
                    Status.INVALID_ARGUMENT.withDescription(validationError).asRuntimeException());
            return;
        }
        try {
            RegisterAgentResult result = registerAgentUseCase.register(new RegisterAgentCommand(
                    request.getEnrollmentToken(),
                    request.getHostname(),
                    toOsPlatform(request.getOsPlatform()),
                    request.getAgentVersion(),
                    request.getHardwareUuid().isBlank() ? null : request.getHardwareUuid()));
            responseObserver.onNext(RegisterAgentResponse.newBuilder()
                    .setAgentId(result.agentId().toString())
                    .setAgentToken(result.agentToken())
                    .setTokenExpiresAt(toTimestamp(result.tokenExpiresAt()))
                    .build());
            responseObserver.onCompleted();
        } catch (InvalidEnrollmentTokenException | EnrollmentTokenExpiredException
                | EnrollmentTokenAlreadyRedeemedException e) {
            // 등록 토큰이 RegisterAgent의 유일한 인증 수단이라, 이 셋은 INVALID_ARGUMENT가 아니라
            // UNAUTHENTICATED다 — 요청 형식은 멀쩡하지만 인증 자격이 유효하지 않다는 뜻이라서다.
            log.warn("RegisterAgent 인증 실패: {}", e.getMessage());
            responseObserver.onError(Status.UNAUTHENTICATED.withDescription(e.getMessage()).asRuntimeException());
        } catch (RuntimeException e) {
            log.error("RegisterAgent 실패", e);
            responseObserver.onError(Status.INTERNAL.withCause(e).asRuntimeException());
        }
    }

    @Override
    public void heartbeat(HeartbeatRequest request, StreamObserver<HeartbeatResponse> responseObserver) {
        if (request.getAgentVersion().isBlank()) {
            responseObserver.onError(
                    Status.INVALID_ARGUMENT.withDescription("agent_version must not be blank").asRuntimeException());
            return;
        }
        if (request.getAgentVersion().length() > MAX_AGENT_VERSION_LENGTH) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("agent_version must be at most " + MAX_AGENT_VERSION_LENGTH + " characters")
                    .asRuntimeException());
            return;
        }
        UUID agentId = AgentAuthContext.AGENT_ID.get();
        try {
            Instant serverTime = heartbeatUseCase.heartbeat(new HeartbeatCommand(agentId, request.getAgentVersion()));
            responseObserver.onNext(
                    HeartbeatResponse.newBuilder().setServerTime(toTimestamp(serverTime)).build());
            responseObserver.onCompleted();
        } catch (AgentNotFoundException | AgentRevokedException e) {
            // AgentAuthServerInterceptor와 같은 원칙 — 구체적 사유는 서버 로그에만, 클라이언트에는
            // 고정된 설명만 보낸다(e.getMessage()를 그대로 보내면 REVOKED 여부 등이 노출된다).
            log.warn("Heartbeat 인증 실패 agent_id={}: {}", agentId, e.getMessage());
            responseObserver.onError(Status.UNAUTHENTICATED.withDescription("인증 실패").asRuntimeException());
        } catch (RuntimeException e) {
            log.error("Heartbeat 실패 agent_id={}", agentId, e);
            responseObserver.onError(Status.INTERNAL.withCause(e).asRuntimeException());
        }
    }

    @Override
    public void refreshAgentToken(
            RefreshAgentTokenRequest request, StreamObserver<RefreshAgentTokenResponse> responseObserver) {
        responseObserver.onError(
                Status.UNIMPLEMENTED.withDescription("S4로 연기됨").asRuntimeException());
    }

    @Override
    public void getPolicy(GetPolicyRequest request, StreamObserver<GetPolicyResponse> responseObserver) {
        try {
            GetPolicyResponse.Builder response = GetPolicyResponse.newBuilder();
            // file_type_policies는 후속 PR에서 채운다. 지금은 빈 목록이다.
            for (ActiveBypassHost host : getPolicyUseCase.bypassHosts()) {
                response.addBypassHosts(BypassHost.newBuilder()
                        .setHost(host.host())
                        .setCategory(toProtoCategory(host.category()))
                        .build());
            }
            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        } catch (RuntimeException e) {
            // 알 수 없는 bypass category처럼 서버 데이터 이상도 여기로 온다 — 틀린 정책을 내려주지 않고 실패시킨다.
            log.error("GetPolicy 실패 agent_id={}", AgentAuthContext.AGENT_ID.get(), e);
            responseObserver.onError(Status.INTERNAL.withCause(e).asRuntimeException());
        }
    }

    private static String validationError(RegisterAgentRequest request) {
        if (request.getEnrollmentToken().isBlank()) {
            return "enrollment_token must not be blank";
        }
        if (request.getHostname().isBlank()) {
            return "hostname must not be blank";
        }
        if (request.getHostname().length() > MAX_HOSTNAME_LENGTH) {
            return "hostname must be at most " + MAX_HOSTNAME_LENGTH + " characters";
        }
        if (request.getAgentVersion().isBlank()) {
            return "agent_version must not be blank";
        }
        if (request.getAgentVersion().length() > MAX_AGENT_VERSION_LENGTH) {
            return "agent_version must be at most " + MAX_AGENT_VERSION_LENGTH + " characters";
        }
        if (request.getHardwareUuid().length() > MAX_HARDWARE_UUID_LENGTH) {
            return "hardware_uuid must be at most " + MAX_HARDWARE_UUID_LENGTH + " characters";
        }
        if (request.getOsPlatform() == OsPlatform.OS_PLATFORM_UNSPECIFIED) {
            return "os_platform must be set";
        }
        if (request.getOsPlatform() == OsPlatform.UNRECOGNIZED) {
            return "os_platform has an unrecognized value";
        }
        return null;
    }

    private static String toOsPlatform(OsPlatform osPlatform) {
        return switch (osPlatform) {
            case OS_PLATFORM_WINDOWS -> "WINDOWS";
            case OS_PLATFORM_MACOS -> "MACOS";
            case OS_PLATFORM_LINUX -> "LINUX";
            case OS_PLATFORM_UNSPECIFIED, UNRECOGNIZED ->
                throw new IllegalArgumentException("unspecified os_platform reached mapping");
        };
    }

    // default를 두지 않는다 — BypassCategory에 값이 추가되면 여기서 컴파일 오류가 나서 변환 누락을 막는다.
    // 이름이 같은 proto enum은 도메인 enum과 구분하려고 전체 이름으로 쓴다.
    private static com.teecherteamc.platform.proto.agent.v1.BypassCategory toProtoCategory(BypassCategory category) {
        return switch (category) {
            case PINNED -> com.teecherteamc.platform.proto.agent.v1.BypassCategory.BYPASS_CATEGORY_PINNED;
            case SECURITY_UPDATE ->
                com.teecherteamc.platform.proto.agent.v1.BypassCategory.BYPASS_CATEGORY_SECURITY_UPDATE;
        };
    }

    private static Timestamp toTimestamp(Instant instant) {
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }
}
