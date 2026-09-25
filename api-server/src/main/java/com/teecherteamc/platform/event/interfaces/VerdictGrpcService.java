package com.teecherteamc.platform.event.interfaces;

import com.google.protobuf.Timestamp;
import com.teecherteamc.platform.event.application.PlaceholderAgent;
import com.teecherteamc.platform.event.application.ReportEventCommand;
import com.teecherteamc.platform.event.application.ReportEventUseCase;
import com.teecherteamc.platform.proto.verdict.v1.DecisionSource;
import com.teecherteamc.platform.proto.verdict.v1.FinalDecision;
import com.teecherteamc.platform.proto.verdict.v1.ReportEventRequest;
import com.teecherteamc.platform.proto.verdict.v1.ReportEventResponse;
import com.teecherteamc.platform.proto.verdict.v1.VerdictServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.grpc.server.service.GrpcService;

/** VerdictService의 ReportEvent만 구현한다. CheckHash/SubmitFile은 아직 미구현(UNIMPLEMENTED)으로 남겨둔다. */
@GrpcService
public class VerdictGrpcService extends VerdictServiceGrpc.VerdictServiceImplBase {

    private final ReportEventUseCase reportEventUseCase;

    public VerdictGrpcService(ReportEventUseCase reportEventUseCase) {
        this.reportEventUseCase = reportEventUseCase;
    }

    @Override
    public void reportEvent(ReportEventRequest request, StreamObserver<ReportEventResponse> responseObserver) {
        try {
            if (request.getDecision() == FinalDecision.FINAL_DECISION_UNSPECIFIED) {
                responseObserver.onError(
                        Status.INVALID_ARGUMENT.withDescription("decision must be set").asRuntimeException());
                return;
            }
            if (!request.hasHeldAt()) {
                responseObserver.onError(
                        Status.INVALID_ARGUMENT.withDescription("held_at must be set").asRuntimeException());
                return;
            }
            reportEventUseCase.reportEvent(toCommand(request));
            responseObserver.onNext(ReportEventResponse.getDefaultInstance());
            responseObserver.onCompleted();
        } catch (DataIntegrityViolationException e) {
            responseObserver.onError(Status.ALREADY_EXISTS
                    .withDescription("event_id already reported")
                    .withCause(e)
                    .asRuntimeException());
        } catch (RuntimeException e) {
            responseObserver.onError(Status.INTERNAL.withCause(e).asRuntimeException());
        }
    }

    private ReportEventCommand toCommand(ReportEventRequest request) {
        return new ReportEventCommand(
                UUID.fromString(request.getEventId()),
                // TODO(sprint-2): RegisterAgent/agent_token 인증이 생기면 Bearer 토큰에서 유도한 agent_id로 교체
                PlaceholderAgent.DEV_AGENT_ID,
                request.getSha256().isBlank() ? null : request.getSha256(),
                request.getRequestHost(),
                request.getUrl(),
                request.getFilename(),
                request.getMimeType(),
                request.getContentDisposition(),
                request.getFileSize(),
                toDecision(request.getDecision()),
                toDecisionSource(request.getDecisionSource()),
                request.getCacheHit(),
                request.getBytesUploaded(),
                toInstant(request.getHeldAt()),
                request.hasDecidedAt() ? toInstant(request.getDecidedAt()) : null);
    }

    private static String toDecision(FinalDecision decision) {
        return switch (decision) {
            case FINAL_DECISION_RELEASED -> "RELEASED";
            case FINAL_DECISION_BLOCKED -> "BLOCKED";
            case FINAL_DECISION_BYPASSED -> "BYPASSED";
            case FINAL_DECISION_FAIL_CLOSE -> "FAIL_CLOSE";
            case FINAL_DECISION_UNSPECIFIED, UNRECOGNIZED ->
                throw new IllegalArgumentException("unspecified decision reached mapping");
        };
    }

    private static String toDecisionSource(DecisionSource source) {
        return switch (source) {
            case DECISION_SOURCE_WHITELIST -> "WHITELIST";
            case DECISION_SOURCE_BLACKLIST -> "BLACKLIST";
            case DECISION_SOURCE_CACHE -> "CACHE";
            case DECISION_SOURCE_ENGINE -> "ENGINE";
            case DECISION_SOURCE_POLICY -> "POLICY";
            case DECISION_SOURCE_UNSPECIFIED, UNRECOGNIZED -> null;
        };
    }

    private static Instant toInstant(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }
}
