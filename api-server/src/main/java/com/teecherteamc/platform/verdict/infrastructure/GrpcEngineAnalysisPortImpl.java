package com.teecherteamc.platform.verdict.infrastructure;

import com.google.protobuf.ByteString;
import com.teecherteamc.platform.proto.engine.v1.AnalysisStatus;
import com.teecherteamc.platform.proto.engine.v1.AnalyzeMetadata;
import com.teecherteamc.platform.proto.engine.v1.AnalyzeRequest;
import com.teecherteamc.platform.proto.engine.v1.AnalyzeResponse;
import com.teecherteamc.platform.proto.engine.v1.DetectionEngineGrpc;
import com.teecherteamc.platform.proto.engine.v1.EngineVerdict;
import com.teecherteamc.platform.ruleset.application.ActiveRulesetService;
import com.teecherteamc.platform.ruleset.domain.EngineRulesetPort;
import com.teecherteamc.platform.ruleset.domain.RulesetSnapshot;
import com.teecherteamc.platform.verdict.domain.AnalysisOutcome;
import com.teecherteamc.platform.verdict.domain.EngineAnalysisPort;
import com.teecherteamc.platform.verdict.domain.EngineMatch;
import com.teecherteamc.platform.verdict.domain.EngineUnavailableException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/**
 * 탐지 엔진의 Analyze(클라이언트 스트리밍)를 호출한다. 스풀 파일을 청크로 읽어서 그대로 흘려보낸다
 * (메모리에 전체를 올리지 않는다). 응답은 스트림 종료 후 1개뿐이라, 콜백 기반 비동기 스텁을
 * CompletableFuture로 감싸서 동기 호출처럼 기다린다. 엔진이 다른 룰셋을 들고 있어
 * FAILED_PRECONDITION으로 거부하면 활성 룰셋을 적재하고 한 번만 다시 보낸다.
 */
@Component
class GrpcEngineAnalysisPortImpl implements EngineAnalysisPort {

    private static final int CHUNK_SIZE = 64 * 1024;
    private static final long ANALYZE_TIMEOUT_SECONDS = 60;

    private final DetectionEngineGrpc.DetectionEngineStub stub;
    private final ActiveRulesetService activeRulesetService;
    private final EngineRulesetPort engineRulesetPort;

    GrpcEngineAnalysisPortImpl(DetectionEngineGrpc.DetectionEngineStub stub,
            ActiveRulesetService activeRulesetService, EngineRulesetPort engineRulesetPort) {
        this.stub = stub;
        this.activeRulesetService = activeRulesetService;
        this.engineRulesetPort = engineRulesetPort;
    }

    @Override
    public AnalysisOutcome analyze(Path spoolFile, long fileSize) {
        // 이 메서드 안에서 나는 예외는 전부 EngineUnavailableException으로 모은다 — 스트림 전송 실패,
        // 엔진이 보낸 status/verdict가 이상한 값이라 toOutcome()이 못 알아듣는 경우까지 전부 포함해서.
        // 호출자(SubmitFileUseCase)가 이 타입 하나만 잡아서 감사 기록(file_verdicts ERROR/analyses
        // TIMEOUT)을 남기므로, 다른 타입으로 새면 그 기록이 조용히 빠진다.
        try {
            RulesetSnapshot snapshot = activeRulesetService.activeSnapshot();
            AnalyzeResponse response;
            try {
                response = send(spoolFile, fileSize, snapshot.key());
            } catch (ExecutionException e) {
                if (!isFailedPrecondition(e.getCause())) {
                    throw new EngineUnavailableException("엔진 호출 실패", e.getCause());
                }
                // 엔진이 다른 룰셋을 들고 있다 — fail-close 예외 규칙의 재시도가 아니라, 룰셋을 맞춘 뒤
                // 딱 한 번만 다시 보낸다. 이 재전송은 FAILED_PRECONDITION에만 허용된다.
                response = resendAfterLoad(spoolFile, fileSize, snapshot);
            }
            return toOutcome(response);
        } catch (EngineUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new EngineUnavailableException("엔진 호출 중 예기치 못한 오류", e);
        }
    }

    private AnalyzeResponse resendAfterLoad(Path spoolFile, long fileSize, RulesetSnapshot snapshot) {
        try {
            engineRulesetPort.load(snapshot);
        } catch (RuntimeException e) {
            throw new EngineUnavailableException("엔진 룰셋 적재 실패", e);
        }
        try {
            return send(spoolFile, fileSize, snapshot.key());
        } catch (ExecutionException e) {
            throw new EngineUnavailableException("룰셋 적재 후 엔진 호출 실패", e.getCause());
        }
    }

    private static boolean isFailedPrecondition(Throwable cause) {
        return cause instanceof StatusRuntimeException statusException
                && statusException.getStatus().getCode() == Status.Code.FAILED_PRECONDITION;
    }

    /** 새 스트림을 열어 스풀 파일을 처음부터 보내고 응답을 기다린다. 엔진이 오류로 끝내면 ExecutionException. */
    private AnalyzeResponse send(Path spoolFile, long fileSize, String rulesetKey) throws ExecutionException {
        CompletableFuture<AnalyzeResponse> future = new CompletableFuture<>();
        StreamObserver<AnalyzeRequest> requestObserver = stub.withDeadlineAfter(
                        ANALYZE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .analyze(new StreamObserver<>() {
                    @Override
                    public void onNext(AnalyzeResponse value) {
                        future.complete(value);
                    }

                    @Override
                    public void onError(Throwable t) {
                        future.completeExceptionally(t);
                    }

                    @Override
                    public void onCompleted() {
                        // 응답은 onNext로 이미 받는다 — 단일 응답 스트림이라 여기선 할 일이 없다.
                    }
                });

        try {
            streamSpoolFile(spoolFile, fileSize, rulesetKey, requestObserver);
        } catch (RuntimeException e) {
            // 엔진이 먼저 abort하면 전송 쪽도 같이 깨질 수 있다 — 엔진이 준 오류가 있으면 그쪽이 원인이다.
            if (!future.isCompletedExceptionally()) {
                throw e;
            }
        }

        try {
            return future.get(ANALYZE_TIMEOUT_SECONDS + 5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new EngineUnavailableException("엔진 응답 시간 초과", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EngineUnavailableException("엔진 호출이 인터럽트됐다", e);
        }
    }

    private void streamSpoolFile(Path spoolFile, long fileSize, String rulesetKey,
            StreamObserver<AnalyzeRequest> requestObserver) {
        try {
            requestObserver.onNext(AnalyzeRequest.newBuilder()
                    .setMetadata(AnalyzeMetadata.newBuilder()
                            .setFileSize(fileSize)
                            .setRulesetKey(rulesetKey)
                            .build())
                    .build());
            try (InputStream in = Files.newInputStream(spoolFile)) {
                byte[] buffer = new byte[CHUNK_SIZE];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    requestObserver.onNext(AnalyzeRequest.newBuilder()
                            .setChunk(ByteString.copyFrom(buffer, 0, read))
                            .build());
                }
            }
            requestObserver.onCompleted();
        } catch (IOException e) {
            requestObserver.onError(e);
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            requestObserver.onError(e);
            throw e;
        }
    }

    private static AnalysisOutcome toOutcome(AnalyzeResponse response) {
        return new AnalysisOutcome(
                toStatus(response.getStatus()),
                toVerdict(response.getVerdict()),
                response.getEngineVersion(),
                response.getRulesetsVersion(),
                response.getRulesetKey(),
                response.getDetectedFileType().isBlank() ? null : response.getDetectedFileType(),
                response.getPeParsed(),
                response.hasMaxSectionEntropy() ? response.getMaxSectionEntropy() : null,
                response.getMatchesList().stream()
                        .map(m -> new EngineMatch(m.getRuleName(), m.getSeverity(), m.getMatchedStringsList()))
                        .toList(),
                response.getDurationMs(),
                response.getErrorMessage().isBlank() ? null : response.getErrorMessage());
    }

    private static String toStatus(AnalysisStatus status) {
        return switch (status) {
            case ANALYSIS_STATUS_SUCCESS -> "SUCCESS";
            case ANALYSIS_STATUS_TIMEOUT -> "TIMEOUT";
            case ANALYSIS_STATUS_CRASH -> "CRASH";
            case ANALYSIS_STATUS_OOM -> "OOM";
            case ANALYSIS_STATUS_UNSUPPORTED -> "UNSUPPORTED";
            case ANALYSIS_STATUS_UNSPECIFIED, UNRECOGNIZED ->
                throw new IllegalStateException("엔진이 status를 지정하지 않았다");
        };
    }

    private static String toVerdict(EngineVerdict verdict) {
        return switch (verdict) {
            case ENGINE_VERDICT_CLEAN -> "CLEAN";
            case ENGINE_VERDICT_MALICIOUS -> "MALICIOUS";
            case ENGINE_VERDICT_SUSPICIOUS -> "SUSPICIOUS";
            case ENGINE_VERDICT_UNKNOWN -> "UNKNOWN";
            case ENGINE_VERDICT_ERROR -> "ERROR";
            case ENGINE_VERDICT_UNSPECIFIED, UNRECOGNIZED ->
                throw new IllegalStateException("엔진이 verdict를 지정하지 않았다");
        };
    }
}
