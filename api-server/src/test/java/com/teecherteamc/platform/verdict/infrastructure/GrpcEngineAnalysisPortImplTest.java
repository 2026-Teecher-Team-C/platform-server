package com.teecherteamc.platform.verdict.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.teecherteamc.platform.proto.engine.v1.AnalysisStatus;
import com.teecherteamc.platform.proto.engine.v1.AnalyzeRequest;
import com.teecherteamc.platform.proto.engine.v1.AnalyzeResponse;
import com.teecherteamc.platform.proto.engine.v1.DetectionEngineGrpc;
import com.teecherteamc.platform.proto.engine.v1.EngineVerdict;
import com.teecherteamc.platform.ruleset.application.ActiveRulesetService;
import com.teecherteamc.platform.ruleset.domain.EngineRulesetPort;
import com.teecherteamc.platform.ruleset.domain.RulesetSnapshot;
import com.teecherteamc.platform.verdict.domain.AnalysisOutcome;
import com.teecherteamc.platform.verdict.domain.EngineUnavailableException;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GrpcEngineAnalysisPortImplTest {

    private static final int CHUNK = 64 * 1024;
    private static final int CHUNKS = 40; // 2.5MB

    @TempDir
    Path tempDir;

    private Server server;
    private ManagedChannel channel;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (channel != null) {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
        if (server != null) {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void 엔진이_받지_않은_청크는_api_server가_미리_보내지_않는다() throws Exception {
        // 엔진은 처음에 메시지를 하나도 요청하지 않는다(바쁜 엔진 흉내). release가 열리면 전부 받는다.
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<ServerCallStreamObserver<AnalyzeResponse>> serverCall = new AtomicReference<>();
        CountDownLatch callStarted = new CountDownLatch(1);
        AtomicInteger sent = new AtomicInteger();
        GrpcEngineAnalysisPortImpl port = portAgainst(new SlowEngine(serverCall, callStarted), sent);
        Path spool = spoolFile();

        CompletableFuture<AnalysisOutcome> result = CompletableFuture.supplyAsync(
                () -> port.analyze(spool, (long) CHUNK * CHUNKS));

        assertThat(callStarted.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(500); // 흐름 제어가 없다면 이 사이에 청크 40개를 전부 보낸다
        int sentWhileEngineBusy = sent.get();

        serverCall.get().request(Integer.MAX_VALUE);
        AnalysisOutcome outcome = result.get(10, TimeUnit.SECONDS);

        // metadata 1개 + 전송 창(in-process는 요청 수 기준)만큼만 나가야 한다. 전부(41개)가 나가면 실패.
        assertThat(sentWhileEngineBusy).isLessThanOrEqualTo(2);
        assertThat(sent.get()).isEqualTo(CHUNKS + 1);
        assertThat(outcome.status()).isEqualTo("SUCCESS");
    }

    @Test
    void 엔진이_청크를_받기_전에_오류로_끝내면_기다리지_않고_엔진_실패로_끝난다() throws Exception {
        AtomicInteger sent = new AtomicInteger();
        GrpcEngineAnalysisPortImpl port = portAgainst(new DetectionEngineGrpc.DetectionEngineImplBase() {
            @Override
            public StreamObserver<AnalyzeRequest> analyze(StreamObserver<AnalyzeResponse> responseObserver) {
                ((ServerCallStreamObserver<AnalyzeResponse>) responseObserver).disableAutoRequest();
                responseObserver.onError(Status.RESOURCE_EXHAUSTED.asRuntimeException());
                return new StreamObserver<>() {
                    @Override
                    public void onNext(AnalyzeRequest value) {
                    }

                    @Override
                    public void onError(Throwable t) {
                    }

                    @Override
                    public void onCompleted() {
                    }
                };
            }
        }, sent);
        Path spool = spoolFile();

        long started = System.nanoTime();
        assertThatThrownBy(() -> port.analyze(spool, (long) CHUNK * CHUNKS))
                .isInstanceOf(EngineUnavailableException.class);
        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)).isLessThan(5);
    }

    private Path spoolFile() throws Exception {
        Path spool = tempDir.resolve("spool.bin");
        Files.write(spool, new byte[CHUNK * CHUNKS]);
        return spool;
    }

    private GrpcEngineAnalysisPortImpl portAgainst(DetectionEngineGrpc.DetectionEngineImplBase engine,
            AtomicInteger sentCounter) throws Exception {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor().addService(engine).build().start();
        channel = InProcessChannelBuilder.forName(name).build();
        ActiveRulesetService rulesets = mock(ActiveRulesetService.class);
        when(rulesets.activeSnapshot()).thenReturn(new RulesetSnapshot(1, "rule a { condition: true }", List.of()));
        DetectionEngineGrpc.DetectionEngineStub stub = DetectionEngineGrpc.newStub(channel)
                .withInterceptors(countingSends(sentCounter));
        return new GrpcEngineAnalysisPortImpl(stub, rulesets, mock(EngineRulesetPort.class));
    }

    private static ClientInterceptor countingSends(AtomicInteger counter) {
        return new ClientInterceptor() {
            @Override
            public <Q, R> ClientCall<Q, R> interceptCall(MethodDescriptor<Q, R> method, CallOptions options,
                    Channel next) {
                return new SimpleForwardingClientCall<>(next.newCall(method, options)) {
                    @Override
                    public void sendMessage(Q message) {
                        counter.incrementAndGet();
                        super.sendMessage(message);
                    }
                };
            }
        };
    }

    /** 자동 요청을 끄고, 테스트가 request()를 부를 때까지 메시지를 받지 않는 엔진. */
    private static final class SlowEngine extends DetectionEngineGrpc.DetectionEngineImplBase {

        private final AtomicReference<ServerCallStreamObserver<AnalyzeResponse>> callRef;
        private final CountDownLatch started;

        SlowEngine(AtomicReference<ServerCallStreamObserver<AnalyzeResponse>> callRef, CountDownLatch started) {
            this.callRef = callRef;
            this.started = started;
        }

        @Override
        public StreamObserver<AnalyzeRequest> analyze(StreamObserver<AnalyzeResponse> responseObserver) {
            ServerCallStreamObserver<AnalyzeResponse> call = (ServerCallStreamObserver<AnalyzeResponse>) responseObserver;
            call.disableAutoRequest();
            callRef.set(call);
            started.countDown();
            return new StreamObserver<>() {
                @Override
                public void onNext(AnalyzeRequest value) {
                }

                @Override
                public void onError(Throwable t) {
                }

                @Override
                public void onCompleted() {
                    call.onNext(AnalyzeResponse.newBuilder()
                            .setStatus(AnalysisStatus.ANALYSIS_STATUS_SUCCESS)
                            .setVerdict(EngineVerdict.ENGINE_VERDICT_CLEAN)
                            .build());
                    call.onCompleted();
                }
            };
        }
    }
}
