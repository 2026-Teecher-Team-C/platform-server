package com.teecherteamc.platform.fleet.interfaces;

import com.google.protobuf.Timestamp;
import com.teecherteamc.platform.fleet.application.RegisterAgentCommand;
import com.teecherteamc.platform.fleet.application.RegisterAgentResult;
import com.teecherteamc.platform.fleet.application.RegisterAgentUseCase;
import com.teecherteamc.platform.fleet.domain.EnrollmentTokenAlreadyRedeemedException;
import com.teecherteamc.platform.fleet.domain.EnrollmentTokenExpiredException;
import com.teecherteamc.platform.fleet.domain.InvalidEnrollmentTokenException;
import com.teecherteamc.platform.proto.agent.v1.AgentServiceGrpc;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.service.GrpcService;

/**
 * AgentService의 gRPC 어댑터. RegisterAgent만 실제 구현한다 — Heartbeat/RefreshAgentToken/GetPolicy는
 * proto가 요구하는 메서드지만 각각 별도 슬라이스(Heartbeat는 다음 PR, 나머지는 S4)라 지금은 명시적으로
 * UNIMPLEMENTED를 돌려준다.
 */
@GrpcService
public class AgentGrpcService extends AgentServiceGrpc.AgentServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(AgentGrpcService.class);
    // devices.hostname/agents.agent_version/devices.hardware_uuid 컬럼 길이와 맞춘다 — 안 걸러내면
    // DB flush 시점에야 터져서 INVALID_ARGUMENT 대신 INTERNAL로 잘못 응답하게 된다.
    private static final int MAX_HOSTNAME_LENGTH = 255;
    private static final int MAX_AGENT_VERSION_LENGTH = 32;
    private static final int MAX_HARDWARE_UUID_LENGTH = 64;

    private final RegisterAgentUseCase registerAgentUseCase;

    public AgentGrpcService(RegisterAgentUseCase registerAgentUseCase) {
        this.registerAgentUseCase = registerAgentUseCase;
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
        responseObserver.onError(
                Status.UNIMPLEMENTED.withDescription("다음 PR에서 구현됨").asRuntimeException());
    }

    @Override
    public void refreshAgentToken(
            RefreshAgentTokenRequest request, StreamObserver<RefreshAgentTokenResponse> responseObserver) {
        responseObserver.onError(
                Status.UNIMPLEMENTED.withDescription("S4로 연기됨").asRuntimeException());
    }

    @Override
    public void getPolicy(GetPolicyRequest request, StreamObserver<GetPolicyResponse> responseObserver) {
        responseObserver.onError(
                Status.UNIMPLEMENTED.withDescription("S4로 연기됨").asRuntimeException());
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

    private static Timestamp toTimestamp(Instant instant) {
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }
}
