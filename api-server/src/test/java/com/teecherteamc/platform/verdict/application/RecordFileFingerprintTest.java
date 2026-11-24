package com.teecherteamc.platform.verdict.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.teecherteamc.platform.verdict.domain.FileFingerprintStore;
import com.teecherteamc.platform.verdict.domain.FingerprintCalculation;
import com.teecherteamc.platform.verdict.domain.TlshCalculationPort;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** TLSH 계산은 CPU만 쓰므로, 동시에 몰려도 정해진 수만 계산하고 나머지는 순서를 기다린다. */
class RecordFileFingerprintTest {

    @Test
    void 동시에_계산하는_지문은_허용_수를_넘지_않는다() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        HeldCalculator calculator = new HeldCalculator(release);
        FileFingerprintStore store = mock(FileFingerprintStore.class);
        RecordFileFingerprint record = new RecordFileFingerprint(calculator, store, 2);

        List<CompletableFuture<Void>> calls = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String sha = "sha" + i;
            calls.add(CompletableFuture.runAsync(() -> record.recordVerified(sha, Path.of("unused"))));
        }
        waitUntil(() -> calculator.active.get() >= 2);
        Thread.sleep(300); // 제한이 없다면 이 사이에 나머지 3건도 계산에 들어간다
        int maxWhileHeld = calculator.maxActive.get();
        release.countDown();
        CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);

        assertThat(maxWhileHeld).isEqualTo(2);
        assertThat(calculator.started.get()).isEqualTo(5);
        verify(store, times(5)).saveVerified(any(), eq(new FingerprintCalculation("T1", "CALCULATED")));
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

    /** release가 열릴 때까지 계산을 붙잡고, 동시에 계산 중인 수를 센다. */
    private static final class HeldCalculator implements TlshCalculationPort {

        final AtomicInteger started = new AtomicInteger();
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maxActive = new AtomicInteger();
        private final CountDownLatch release;

        HeldCalculator(CountDownLatch release) {
            this.release = release;
        }

        @Override
        public FingerprintCalculation calculate(Path file) {
            started.incrementAndGet();
            maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            active.decrementAndGet();
            return new FingerprintCalculation("T1", "CALCULATED");
        }
    }
}
