package com.teecherteamc.platform.verdict.interfaces;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.teecherteamc.platform.event.application.PlaceholderAgent;
import com.teecherteamc.platform.event.application.ReportEventCommand;
import com.teecherteamc.platform.event.application.ReportEventUseCase;
import com.teecherteamc.platform.hashlist.domain.HashKey;
import com.teecherteamc.platform.hashlist.domain.InvalidHashException;
import com.teecherteamc.platform.lookup.application.CheckHashResult;
import com.teecherteamc.platform.lookup.application.CheckHashUseCase;
import com.teecherteamc.platform.proto.verdict.v1.CheckHashRequest;
import com.teecherteamc.platform.proto.verdict.v1.CheckHashResponse;
import com.teecherteamc.platform.proto.verdict.v1.Decision;
import com.teecherteamc.platform.proto.verdict.v1.DecisionSource;
import com.teecherteamc.platform.proto.verdict.v1.FinalDecision;
import com.teecherteamc.platform.proto.verdict.v1.ReportEventRequest;
import com.teecherteamc.platform.proto.verdict.v1.ReportEventResponse;
import com.teecherteamc.platform.proto.verdict.v1.SubmitFileMetadata;
import com.teecherteamc.platform.proto.verdict.v1.SubmitFileRequest;
import com.teecherteamc.platform.proto.verdict.v1.SubmitFileResponse;
import com.teecherteamc.platform.proto.verdict.v1.VerdictServiceGrpc;
import com.teecherteamc.platform.verdict.application.SubmitFileCommand;
import com.teecherteamc.platform.verdict.application.SubmitFileResult;
import com.teecherteamc.platform.verdict.application.SubmitFileUseCase;
import com.teecherteamc.platform.verdict.domain.EngineUnavailableException;
import com.teecherteamc.platform.verdict.domain.HashMismatchException;
import com.teecherteamc.platform.verdict.domain.SpoolPort;
import com.teecherteamc.platform.verdict.domain.SpoolResult;
import com.teecherteamc.platform.verdict.domain.SpoolSession;
import com.teecherteamc.platform.verdict.domain.SpoolSizeExceededException;
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

/**
 * VerdictService(CheckHash/SubmitFile/ReportEvent) 전체의 gRPC 어댑터. gRPC는 서비스당 구현 빈이
 * 하나여야 해서, 3개 RPC가 각자 다른 컨텍스트(verdict/lookup, event) 소관이어도 이 클래스 하나에
 * 모인다 — 여기는 프로토콜 변환만 하고, 실제 로직은 각 컨텍스트의 유스케이스에 위임한다.
 */
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
    private final CheckHashUseCase checkHashUseCase;
    private final SubmitFileUseCase submitFileUseCase;
    private final SpoolPort spoolPort;

    public VerdictGrpcService(
            ReportEventUseCase reportEventUseCase,
            CheckHashUseCase checkHashUseCase,
            SubmitFileUseCase submitFileUseCase,
            SpoolPort spoolPort) {
        this.reportEventUseCase = reportEventUseCase;
        this.checkHashUseCase = checkHashUseCase;
        this.submitFileUseCase = submitFileUseCase;
        this.spoolPort = spoolPort;
    }

    @Override
    public StreamObserver<SubmitFileRequest> submitFile(StreamObserver<SubmitFileResponse> responseObserver) {
        return new SubmitFileRequestObserver(responseObserver, spoolPort, submitFileUseCase);
    }

    @Override
    public void checkHash(CheckHashRequest request, StreamObserver<CheckHashResponse> responseObserver) {
        HashKey hashKey;
        try {
            hashKey = HashKey.sha256(request.getSha256());
        } catch (InvalidHashException e) {
            responseObserver.onError(
                    Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            return;
        }
        try {
            CheckHashResult result = checkHashUseCase.checkHash(hashKey.getHashValue());
            responseObserver.onNext(CheckHashResponse.newBuilder()
                    .setDecision(toProtoDecision(result.decision()))
                    .setSource(toProtoDecisionSource(result.source()))
                    .setReason(result.reason() == null ? "" : result.reason())
                    .build());
            responseObserver.onCompleted();
        } catch (RuntimeException e) {
            log.error("CheckHash failed sha256={}", hashKey.getHashValue(), e);
            responseObserver.onError(Status.INTERNAL.withCause(e).asRuntimeException());
        }
    }

    private static Decision toProtoDecision(String decision) {
        return switch (decision) {
            case "ALLOW" -> Decision.DECISION_ALLOW;
            case "BLOCK" -> Decision.DECISION_BLOCK;
            case "UNKNOWN" -> Decision.DECISION_UNKNOWN;
            default -> throw new IllegalStateException("unmapped decision: " + decision);
        };
    }

    private static DecisionSource toProtoDecisionSource(String source) {
        if (source == null) {
            return DecisionSource.DECISION_SOURCE_UNSPECIFIED;
        }
        return switch (source) {
            case "BLACKLIST" -> DecisionSource.DECISION_SOURCE_BLACKLIST;
            case "CACHE" -> DecisionSource.DECISION_SOURCE_CACHE;
            case "ENGINE" -> DecisionSource.DECISION_SOURCE_ENGINE;
            default -> throw new IllegalStateException("unmapped decision source: " + source);
        };
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

    /**
     * SubmitFile의 요청 스트림 수신기. 첫 메시지(metadata)에서 스풀 세션을 열고, 이후 청크를 전부
     * 스풀에 쓰면서 해시를 동시에 계산한다. 스트림이 끝나면(onCompleted) 비로소 유스케이스를 호출한다
     * — 어떤 경로로 끝나든 스풀 파일은 항상 정리한다.
     */
    private static final class SubmitFileRequestObserver implements StreamObserver<SubmitFileRequest> {

        private final StreamObserver<SubmitFileResponse> responseObserver;
        private final SpoolPort spoolPort;
        private final SubmitFileUseCase submitFileUseCase;

        private SpoolSession spoolSession;
        private String claimedSha256;
        private long claimedFileSize;
        private boolean metadataReceived;
        private boolean failed;

        SubmitFileRequestObserver(
                StreamObserver<SubmitFileResponse> responseObserver, SpoolPort spoolPort,
                SubmitFileUseCase submitFileUseCase) {
            this.responseObserver = responseObserver;
            this.spoolPort = spoolPort;
            this.submitFileUseCase = submitFileUseCase;
        }

        @Override
        public void onNext(SubmitFileRequest request) {
            if (failed) {
                return;
            }
            try {
                switch (request.getPayloadCase()) {
                    case METADATA -> handleMetadata(request.getMetadata());
                    case CHUNK -> handleChunk(request.getChunk());
                    case PAYLOAD_NOT_SET -> fail(Status.INVALID_ARGUMENT.withDescription("payload must be set"));
                }
            } catch (SpoolSizeExceededException e) {
                fail(Status.RESOURCE_EXHAUSTED.withDescription(e.getMessage()).withCause(e));
            } catch (RuntimeException e) {
                log.error("SubmitFile 청크 처리 중 오류", e);
                fail(Status.INTERNAL.withCause(e));
            }
        }

        private void handleMetadata(SubmitFileMetadata metadata) {
            if (metadataReceived) {
                fail(Status.INVALID_ARGUMENT.withDescription("metadata는 첫 메시지에만 와야 한다"));
                return;
            }
            HashKey hashKey;
            try {
                hashKey = HashKey.sha256(metadata.getSha256());
            } catch (InvalidHashException e) {
                fail(Status.INVALID_ARGUMENT.withDescription(e.getMessage()));
                return;
            }
            try {
                // analyses.event_id는 FK 순서 문제로 안 쓰지만(아래 onCompleted 참고), 형식은 검증한다.
                UUID.fromString(metadata.getEventId());
            } catch (IllegalArgumentException e) {
                fail(Status.INVALID_ARGUMENT.withDescription("event_id must be a valid UUID"));
                return;
            }
            this.claimedSha256 = hashKey.getHashValue();
            this.claimedFileSize = metadata.getFileSize();
            this.spoolSession = spoolPort.open();
            this.metadataReceived = true;
        }

        private void handleChunk(ByteString chunk) {
            if (!metadataReceived) {
                fail(Status.INVALID_ARGUMENT.withDescription("첫 메시지는 metadata여야 한다"));
                return;
            }
            spoolSession.write(chunk.toByteArray());
        }

        @Override
        public void onError(Throwable t) {
            // 클라이언트(에이전트) 쪽 문제로 스트림이 끊긴 것 — 우리가 이미 onError를 보낸 상태가
            // 아니라면 로그만 남기고 스풀을 정리한다.
            log.warn("SubmitFile 스트림이 끊겼다", t);
            cleanup();
        }

        @Override
        public void onCompleted() {
            if (failed) {
                return;
            }
            if (!metadataReceived) {
                fail(Status.INVALID_ARGUMENT.withDescription("metadata를 받지 못했다"));
                return;
            }
            SpoolResult result = spoolSession.finish();
            // file_size는 사전 정보일 뿐이고 실제 판정엔 실제 수신 바이트(result.bytesWritten())만 쓰지만,
            // 둘이 크게 다르면 에이전트 쪽 버그(또는 조작 시도)일 수 있어 여기서 먼저 걸러낸다.
            if (result.bytesWritten() != claimedFileSize) {
                spoolSession.delete();
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("file_size=%d but received %d bytes".formatted(claimedFileSize, result.bytesWritten()))
                        .asRuntimeException());
                return;
            }
            try {
                SubmitFileResult outcome = submitFileUseCase.submitFile(
                        new SubmitFileCommand(claimedSha256, result.sha256(), result.bytesWritten(), result.path()));
                responseObserver.onNext(toResponse(outcome));
                responseObserver.onCompleted();
            } catch (HashMismatchException e) {
                responseObserver.onError(Status.DATA_LOSS.withDescription(e.getMessage()).asRuntimeException());
            } catch (EngineUnavailableException e) {
                log.error("SubmitFile: 엔진 호출 실패", e);
                responseObserver.onError(
                        Status.UNAVAILABLE.withDescription("탐지 엔진에 연결할 수 없다").withCause(e).asRuntimeException());
            } catch (RuntimeException e) {
                log.error("SubmitFile 처리 실패", e);
                responseObserver.onError(Status.INTERNAL.withCause(e).asRuntimeException());
            } finally {
                spoolSession.delete();
            }
        }

        private void fail(Status status) {
            failed = true;
            cleanup();
            responseObserver.onError(status.asRuntimeException());
        }

        private void cleanup() {
            if (spoolSession != null) {
                spoolSession.delete();
            }
        }

        private static SubmitFileResponse toResponse(SubmitFileResult result) {
            return SubmitFileResponse.newBuilder()
                    .setDecision(toProtoDecision(result.decision()))
                    .setSource(toProtoDecisionSource(result.source()))
                    .setReason(result.reason() == null ? "" : result.reason())
                    .addAllMatchedRules(result.matchedRules())
                    .build();
        }
    }
}
