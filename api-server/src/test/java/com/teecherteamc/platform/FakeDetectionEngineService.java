package com.teecherteamc.platform;

import com.teecherteamc.platform.proto.engine.v1.AnalysisStatus;
import com.teecherteamc.platform.proto.engine.v1.AnalyzeRequest;
import com.teecherteamc.platform.proto.engine.v1.AnalyzeResponse;
import com.teecherteamc.platform.proto.engine.v1.DetectionEngineGrpc;
import com.teecherteamc.platform.proto.engine.v1.EngineVerdict;
import com.teecherteamc.platform.proto.engine.v1.RuleMatch;
import com.teecherteamc.platform.proto.engine.v1.CompileRulesRequest;
import com.teecherteamc.platform.proto.engine.v1.CompileRulesResponse;
import com.teecherteamc.platform.proto.engine.v1.CompiledRule;
import com.teecherteamc.platform.proto.engine.v1.LoadRulesetRequest;
import com.teecherteamc.platform.proto.engine.v1.LoadRulesetResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.grpc.server.service.GrpcService;

/**
 * 테스트 전용 가짜 탐지 엔진. 업로드된 바이트에 {@link #MALICIOUS_MARKER}가 들어있으면 MALICIOUS,
 * 아니면 CLEAN을 반환한다. @AutoConfigureTestGrpcTransport가 모든 gRPC 클라이언트 채널을 같은
 * in-process 서버로 돌려보내므로, 이 빈만 테스트 컨텍스트에 있으면 실제 detection-engine 컨테이너
 * 없이도 SubmitFile을 끝까지 테스트할 수 있다.
 */
@GrpcService
public class FakeDetectionEngineService extends DetectionEngineGrpc.DetectionEngineImplBase {

    static final String MALICIOUS_MARKER = "FAKE-MALICIOUS-MARKER";
    static final String CRASH_MARKER = "FAKE-ENGINE-CRASH-MARKER";
    // status != SUCCESS인데 verdict=CLEAN을 보내는, proto상으로는 가능하지만 모순된 응답을 흉내낸다.
    static final String INCONSISTENT_MARKER = "FAKE-INCONSISTENT-MARKER";

    // --- 룰셋 상태(테스트마다 reset()) ---
    static volatile String loadedKey = "";
    static volatile int loadedVersion = 0;
    static final AtomicInteger loadCount = new AtomicInteger();
    // true면 다음 RPC 한 번(종류 무관)이 UNAVAILABLE로 실패하고 플래그가 꺼진다.
    static volatile boolean failNextCall = false;

    private static final Pattern RULE = Pattern.compile("rule\\s+(\\w+)");

    public static void reset() {
        loadedKey = "";
        loadedVersion = 0;
        loadCount.set(0);
        failNextCall = false;
    }

    public static String loadedKey() {
        return loadedKey;
    }

    public static int loadedVersion() {
        return loadedVersion;
    }

    public static int loadCount() {
        return loadCount.get();
    }

    public static void failNextCall() {
        failNextCall = true;
    }

    /** failNextCall이 켜져 있으면 끄고 true. */
    private static synchronized boolean consumeFailure() {
        if (failNextCall) {
            failNextCall = false;
            return true;
        }
        return false;
    }

    @Override
    public void compileRules(CompileRulesRequest request, StreamObserver<CompileRulesResponse> responseObserver) {
        if (consumeFailure()) {
            responseObserver.onError(Status.UNAVAILABLE.withDescription("fake engine down").asRuntimeException());
            return;
        }
        String source = request.getSource();
        if (source.contains("SYNTAX_ERROR")) {
            responseObserver.onNext(CompileRulesResponse.newBuilder()
                    .setOk(false).setErrorMessage("line 1: syntax error").build());
            responseObserver.onCompleted();
            return;
        }
        CompileRulesResponse.Builder response = CompileRulesResponse.newBuilder().setOk(true);
        Matcher rules = RULE.matcher(source);
        while (rules.find()) {
            // 이 룰 이름 바로 뒤(다음 rule 전)에 나오는 severity만 본다.
            int next = source.indexOf("rule ", rules.end());
            String tail = source.substring(rules.end(), next < 0 ? source.length() : next);
            Matcher severity = Pattern.compile("severity\\s*=\\s*\"(\\w+)\"").matcher(tail);
            response.addRules(CompiledRule.newBuilder()
                    .setName(rules.group(1))
                    .setSeverity(severity.find() ? severity.group(1) : "MEDIUM"));
        }
        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
    }

    @Override
    public void loadRuleset(LoadRulesetRequest request, StreamObserver<LoadRulesetResponse> responseObserver) {
        if (consumeFailure()) {
            responseObserver.onError(Status.UNAVAILABLE.withDescription("fake engine down").asRuntimeException());
            return;
        }
        loadedKey = request.getRulesetKey();
        loadedVersion = request.getRulesetsVersion();
        loadCount.incrementAndGet();
        responseObserver.onNext(LoadRulesetResponse.getDefaultInstance());
        responseObserver.onCompleted();
    }

    @Override
    public StreamObserver<AnalyzeRequest> analyze(StreamObserver<AnalyzeResponse> responseObserver) {
        if (consumeFailure()) {
            responseObserver.onError(Status.UNAVAILABLE.withDescription("fake engine down").asRuntimeException());
            return new StreamObserver<>() {
                @Override public void onNext(AnalyzeRequest request) { }
                @Override public void onError(Throwable t) { }
                @Override public void onCompleted() { }
            };
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        return new StreamObserver<>() {
            private boolean keyMismatch;

            @Override
            public void onNext(AnalyzeRequest request) {
                if (request.getPayloadCase() == AnalyzeRequest.PayloadCase.METADATA) {
                    String key = request.getMetadata().getRulesetKey();
                    if (!key.isEmpty() && !key.equals(loadedKey)) {
                        keyMismatch = true;
                    }
                }
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
                if (keyMismatch) {
                    responseObserver.onError(Status.FAILED_PRECONDITION
                            .withDescription("ruleset key mismatch").asRuntimeException());
                    return;
                }
                String content = buffer.toString(StandardCharsets.UTF_8);
                if (content.contains(CRASH_MARKER)) {
                    // 엔진 호출 자체가 실패하는 상황(연결 끊김·타임아웃 등)을 흉내낸다.
                    responseObserver.onError(Status.UNAVAILABLE.withDescription("fake engine crash").asRuntimeException());
                    return;
                }
                if (content.contains(INCONSISTENT_MARKER)) {
                    responseObserver.onNext(AnalyzeResponse.newBuilder()
                            .setStatus(AnalysisStatus.ANALYSIS_STATUS_UNSUPPORTED)
                            .setVerdict(EngineVerdict.ENGINE_VERDICT_CLEAN)
                            .setEngineVersion("fake-1.0")
                            .setDurationMs(1)
                            .build());
                    responseObserver.onCompleted();
                    return;
                }
                boolean malicious = content.contains(MALICIOUS_MARKER);
                AnalyzeResponse.Builder response = AnalyzeResponse.newBuilder()
                        .setStatus(AnalysisStatus.ANALYSIS_STATUS_SUCCESS)
                        .setVerdict(malicious ? EngineVerdict.ENGINE_VERDICT_MALICIOUS : EngineVerdict.ENGINE_VERDICT_CLEAN)
                        .setEngineVersion("fake-1.0")
                        .setRulesetsVersion(loadedVersion)
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
