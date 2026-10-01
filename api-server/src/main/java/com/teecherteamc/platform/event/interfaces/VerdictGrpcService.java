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
import java.util.regex.Pattern;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.grpc.server.service.GrpcService;

/** VerdictService의 ReportEvent만 구현한다. CheckHash/SubmitFile은 아직 미구현(UNIMPLEMENTED)으로 남겨둔다. */
@GrpcService
public class VerdictGrpcService extends VerdictServiceGrpc.VerdictServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(VerdictGrpcService.class);
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final int MAX_FILENAME_LENGTH = 512;
    private static final String PK_CONSTRAINT_NAME = "pk_download_events";
    // google.protobuf.Timestamp 유효 범위: 0001-01-01T00:00:00Z ~ 9999-12-31T23:59:59.999999999Z
    private static final long MIN_TIMESTAMP_SECONDS = -62135596800L;
    private static final long MAX_TIMESTAMP_SECONDS = 253402300799L;

    private final ReportEventUseCase reportEventUseCase;

    public VerdictGrpcService(ReportEventUseCase reportEventUseCase) {
        this.reportEventUseCase = reportEventUseCase;
    }

    @Override
    public void reportEvent(ReportEventRequest request, StreamObserver<ReportEventResponse> responseObserver) {
        String validationError = validationError(request);
        if (validationError != null) {
            responseObserver.onError(
                    Status.INVALID_ARGUMENT.withDescription(validationError).asRuntimeException());
            return;
        }
        try {
            reportEventUseCase.reportEvent(toCommand(request));
            responseObserver.onNext(ReportEventResponse.getDefaultInstance());
            responseObserver.onCompleted();
        } catch (DataIntegrityViolationException e) {
            // JPA는 PK 중복이든 FK 위반이든 전부 DataIntegrityViolationException으로 번역해버린다
            // (DuplicateKeyException이 아니다). pk_download_events 제약 위반일 때만 진짜 중복이고,
            // 그 외(예: agent_id FK 위반)를 "중복"으로 잘못 보고하면 실제 원인을 찾기 어려워진다.
            if (isPrimaryKeyViolation(e)) {
                log.warn("ReportEvent duplicate event_id={}", request.getEventId(), e);
                responseObserver.onError(Status.ALREADY_EXISTS
                        .withDescription("event_id already reported")
                        .withCause(e)
                        .asRuntimeException());
            } else {
                log.error("ReportEvent failed event_id={}", request.getEventId(), e);
                responseObserver.onError(Status.INTERNAL.withCause(e).asRuntimeException());
            }
        } catch (RuntimeException e) {
            // withCause는 클라이언트로 전송되지 않는다 — 서버 로그에 남기지 않으면 원인을 어디서도 못 본다.
            log.error("ReportEvent failed event_id={}", request.getEventId(), e);
            responseObserver.onError(Status.INTERNAL.withCause(e).asRuntimeException());
        }
    }

    private static boolean isPrimaryKeyViolation(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException cve) {
                return PK_CONSTRAINT_NAME.equals(cve.getConstraintName());
            }
        }
        return false;
    }

    private static boolean isValidTimestamp(Timestamp timestamp) {
        return timestamp.getNanos() >= 0
                && timestamp.getNanos() <= 999_999_999
                && timestamp.getSeconds() >= MIN_TIMESTAMP_SECONDS
                && timestamp.getSeconds() <= MAX_TIMESTAMP_SECONDS;
    }

    /** null이면 유효. 아니면 그 문자열이 INVALID_ARGUMENT 사유가 된다. */
    private String validationError(ReportEventRequest request) {
        if (request.getDecision() == FinalDecision.FINAL_DECISION_UNSPECIFIED) {
            return "decision must be set";
        }
        if (request.getDecision() == FinalDecision.UNRECOGNIZED) {
            return "decision has an unrecognized value";
        }
        if (!request.hasHeldAt()) {
            return "held_at must be set";
        }
        if (!isValidTimestamp(request.getHeldAt())) {
            return "held_at has an out-of-range seconds or nanos value";
        }
        if (request.hasDecidedAt() && !isValidTimestamp(request.getDecidedAt())) {
            return "decided_at has an out-of-range seconds or nanos value";
        }
        try {
            UUID.fromString(request.getEventId());
        } catch (IllegalArgumentException e) {
            return "event_id must be a valid UUID";
        }
        if (request.getRequestHost().isBlank()) {
            return "request_host must not be blank";
        }
        if (request.getUrl().isBlank()) {
            return "url must not be blank";
        }
        String sha256 = request.getSha256();
        if (!sha256.isBlank() && !SHA256_PATTERN.matcher(sha256).matches()) {
            return "sha256 must be 64 lowercase hex characters";
        }
        if (request.getFilename().length() > MAX_FILENAME_LENGTH) {
            return "filename must be at most " + MAX_FILENAME_LENGTH + " characters";
        }
        if (request.getHoldDurationMs() < 0) {
            return "hold_duration_ms must not be negative";
        }
        return null;
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
                request.hasDecidedAt() ? toInstant(request.getDecidedAt()) : null,
                request.getHoldDurationMs());
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
