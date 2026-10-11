package com.teecherteamc.platform;

import com.teecherteamc.platform.proto.engine.v1.AnalysisStatus;
import com.teecherteamc.platform.proto.engine.v1.AnalyzeRequest;
import com.teecherteamc.platform.proto.engine.v1.AnalyzeResponse;
import com.teecherteamc.platform.proto.engine.v1.DetectionEngineGrpc;
import com.teecherteamc.platform.proto.engine.v1.EngineVerdict;
import com.teecherteamc.platform.proto.engine.v1.RuleMatch;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.grpc.server.service.GrpcService;

/**
 * 테스트 전용 가짜 탐지 엔진. 업로드된 바이트에 {@link #MALICIOUS_MARKER}가 들어있으면 MALICIOUS,
 * 아니면 CLEAN을 반환한다. @AutoConfigureTestGrpcTransport가 모든 gRPC 클라이언트 채널을 같은
 * in-process 서버로 돌려보내므로, 이 빈만 테스트 컨텍스트에 있으면 실제 detection-engine 컨테이너
 * 없이도 SubmitFile을 끝까지 테스트할 수 있다.
 */
@GrpcService
class FakeDetectionEngineService extends DetectionEngineGrpc.DetectionEngineImplBase {

    static final String MALICIOUS_MARKER = "FAKE-MALICIOUS-MARKER";
    static final String CRASH_MARKER = "FAKE-ENGINE-CRASH-MARKER";

    @Override
    public StreamObserver<AnalyzeRequest> analyze(StreamObserver<AnalyzeResponse> responseObserver) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        return new StreamObserver<>() {
            @Override
            public void onNext(AnalyzeRequest request) {
                if (request.getPayloadCase() == AnalyzeRequest.PayloadCase.CHUNK) {
                    try {
                        request.getChunk().writeTo(buffer);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            }

            @Override
            public void onError(Throwable t) {
                // 테스트에서 클라이언트가 먼저 끊는 경우는 없다 — no-op.
            }

            @Override
            public void onCompleted() {
                String content = buffer.toString(StandardCharsets.UTF_8);
                if (content.contains(CRASH_MARKER)) {
                    // 엔진 호출 자체가 실패하는 상황(연결 끊김·타임아웃 등)을 흉내낸다.
                    responseObserver.onError(Status.UNAVAILABLE.withDescription("fake engine crash").asRuntimeException());
                    return;
                }
                boolean malicious = content.contains(MALICIOUS_MARKER);
                AnalyzeResponse.Builder response = AnalyzeResponse.newBuilder()
                        .setStatus(AnalysisStatus.ANALYSIS_STATUS_SUCCESS)
                        .setVerdict(malicious ? EngineVerdict.ENGINE_VERDICT_MALICIOUS : EngineVerdict.ENGINE_VERDICT_CLEAN)
                        .setEngineVersion("fake-1.0")
                        .setDurationMs(1);
                if (malicious) {
                    response.addMatches(RuleMatch.newBuilder()
                            .setRuleName("fake_rule")
                            .setSeverity("HIGH")
                            .addMatchedStrings("marker"));
                }
                responseObserver.onNext(response.build());
                responseObserver.onCompleted();
            }
        };
    }
}
