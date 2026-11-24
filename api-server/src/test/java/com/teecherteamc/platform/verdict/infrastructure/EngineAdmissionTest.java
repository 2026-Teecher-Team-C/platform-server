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
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** api-server가 엔진에 동시에 넣는 Analyze 호출 수를 엔진 worker 수 안으로 묶는지 본다. */
class EngineAdmissionTest {

    private static final long FILE_SIZE = 256 * 1024;

    @TempDir
    Path tempDir;

    private final CountDownLatch release = new CountDownLatch(1);
    private final HeldEngine engine = new HeldEngine(release);
    private Server server;
    private ManagedChannel channel;
    private Path spool;

    @BeforeEach
    void setUp() throws Exception {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).addService(engine).build().start();
        channel = InProcessChannelBuilder.forName(name).build();
        spool = tempDir.resolve("spool.bin");
        Files.write(spool, new byte[(int) FILE_SIZE]);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        release.countDown();
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void 엔진에_동시에_들어가는_분석은_허용_수를_넘지_않는다() throws Exception {
        GrpcEngineAnalysisPortImpl port = port(new EngineAdmission(2, Duration.ofSeconds(10)));

        List<CompletableFuture<AnalysisOutcome>> calls = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            calls.add(CompletableFuture.supplyAsync(() -> port.analyze(spool, FILE_SIZE)));
        }
        waitUntil(() -> engine.active.get() == 2);
        Thread.sleep(300); // 제한이 없다면 이 사이에 나머지 3건도 엔진에 들어간다
        int maxWhileHeld = engine.maxActive.get();
        release.countDown();

        for (CompletableFuture<AnalysisOutcome> call : calls) {
            assertThat(call.get(10, TimeUnit.SECONDS).status()).isEqualTo("SUCCESS");
        }
        assertThat(maxWhileHeld).isEqualTo(2);
        assertThat(engine.started.get()).isEqualTo(5);
    }

    @Test
    void 대기_한도_안에_차례가_오지_않으면_엔진을_부르지_않고_엔진_실패로_끝난다() throws Exception {
        GrpcEngineAnalysisPortImpl port = port(new EngineAdmission(1, Duration.ofMillis(200)));
        CompletableFuture<AnalysisOutcome> first = CompletableFuture.supplyAsync(() -> port.analyze(spool, FILE_SIZE));
        waitUntil(() -> engine.active.get() == 1);

        assertThatThrownBy(() -> port.analyze(spool, FILE_SIZE))
                .isInstanceOf(EngineUnavailableException.class);
        assertThat(engine.started.get()).isEqualTo(1);

        release.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo("SUCCESS");
    }

    private GrpcEngineAnalysisPortImpl port(EngineAdmission admission) {
        ActiveRulesetService rulesets = mock(ActiveRulesetService.class);
        when(rulesets.activeSnapshot()).thenReturn(new RulesetSnapshot(1, "rule a { condition: true }", List.of()));
        return new GrpcEngineAnalysisPortImpl(DetectionEngineGrpc.newStub(channel), rulesets,
                mock(EngineRulesetPort.class), admission);
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("조건이 5초 안에 맞지 않았다");
            }
            Thread.sleep(10);
        }
    }

    /** 스트림을 다 받은 뒤 release가 열릴 때까지 응답을 붙잡는 엔진. 동시에 붙잡힌 호출 수를 센다. */
    private static final class HeldEngine extends DetectionEngineGrpc.DetectionEngineImplBase {

        final AtomicInteger started = new AtomicInteger();
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maxActive = new AtomicInteger();
        private final CountDownLatch release;

        HeldEngine(CountDownLatch release) {
            this.release = release;
        }

        @Override
        public StreamObserver<AnalyzeRequest> analyze(StreamObserver<AnalyzeResponse> responseObserver) {
            started.incrementAndGet();
            maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            return new StreamObserver<>() {
                @Override
                public void onNext(AnalyzeRequest value) {
                }

                @Override
                public void onError(Throwable t) {
                    active.decrementAndGet();
                }

                @Override
                public void onCompleted() {
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    active.decrementAndGet();
                    responseObserver.onNext(AnalyzeResponse.newBuilder()
                            .setStatus(AnalysisStatus.ANALYSIS_STATUS_SUCCESS)
                            .setVerdict(EngineVerdict.ENGINE_VERDICT_CLEAN)
                            .build());
                    responseObserver.onCompleted();
                }
            };
        }
    }
}
