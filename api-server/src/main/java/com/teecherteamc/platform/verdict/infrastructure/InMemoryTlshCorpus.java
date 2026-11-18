package com.teecherteamc.platform.verdict.infrastructure;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 메모리 상주 TLSH corpus(2026-11-19 운영 부하 시험: 요청마다 DB에서 후보 10만 건 안팎을 읽고 파싱해 0.75건/s가 상한이었다).
 *
 * <p>스냅샷은 만든 시점의 corpus 토큰에 묶인다. 요청 트랜잭션의 토큰과 같을 때만 내주고, 다르면 비어 있음을 돌려줘
 * 호출자가 DB 경로로 답하게 한다(오래된 목록으로 판정하지 않는다). 그때 백그라운드 재구축을 한 번에 하나,
 * {@link #MIN_REBUILD_INTERVAL_NANOS} 간격으로만 요청한다(대량 등록 중 토큰이 계속 바뀌어도 CPU를 잡아먹지 않게).
 */
@Component
public class InMemoryTlshCorpus implements DisposableBean {

    static final long MIN_REBUILD_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final Logger log = LoggerFactory.getLogger(InMemoryTlshCorpus.class);

    private final Supplier<TlshCorpusSnapshot> loader;
    private final Executor executor;
    private final LongSupplier nanoTime;
    private final AtomicReference<TlshCorpusSnapshot> current = new AtomicReference<>();
    private final AtomicBoolean rebuilding = new AtomicBoolean();
    private volatile long lastRebuildStart;
    private volatile boolean everRebuilt;
    private final Counter hits;
    private final Counter stale;
    private boolean enabled = true;

    @Autowired
    public InMemoryTlshCorpus(JdbcTlshCorpusLoader loader, MeterRegistry registry,
            @Value("${similarity.mode:memory}") String mode, @Value("${similarity.corpus.limit:0}") int corpusLimit) {
        this(loader::loadCommitted, singleThread(), System::nanoTime, registry);
        // JdbcSimilarityDecisionStore와 같은 조건: 상한(시나리오 B)이 있으면 메모리 경로를 안 쓰니 만들지도 않는다.
        this.enabled = "memory".equals(mode) && corpusLimit <= 0;
    }

    public InMemoryTlshCorpus(Supplier<TlshCorpusSnapshot> loader, Executor executor, LongSupplier nanoTime,
            MeterRegistry registry) {
        this.loader = loader;
        this.executor = executor;
        this.nanoTime = nanoTime;
        this.hits = Counter.builder("verdict.similarity.memory").tag("result", "hit").register(registry);
        this.stale = Counter.builder("verdict.similarity.memory").tag("result", "stale").register(registry);
        Gauge.builder("verdict.similarity.memory.size", current, ref -> {
            TlshCorpusSnapshot snapshot = ref.get();
            return snapshot == null ? 0 : snapshot.size();
        }).register(registry);
    }

    /** token과 같은 시점의 스냅샷. 없으면 비어 있고 재구축을 요청한다. */
    public Optional<TlshCorpusSnapshot> snapshotFor(String token) {
        TlshCorpusSnapshot snapshot = current.get();
        if (snapshot != null && snapshot.token().equals(token)) {
            hits.increment();
            return Optional.of(snapshot);
        }
        stale.increment();
        requestRebuild();
        return Optional.empty();
    }

    /** 미리 만든 스냅샷을 넣는다(통합 테스트가 자기 트랜잭션에서 읽은 corpus를 쓸 때). */
    public void install(TlshCorpusSnapshot snapshot) {
        current.set(snapshot);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (enabled) {
            requestRebuild();
        }
    }

    private void requestRebuild() {
        // 깃발을 먼저 잡고 간격을 본다. 반대 순서면 간격 검사와 CAS 사이에 다른 재구축이 끝나 30초를 건너뛸 수 있다.
        if (!rebuilding.compareAndSet(false, true)) {
            return;
        }
        long now = nanoTime.getAsLong();
        if (everRebuilt && now - lastRebuildStart < MIN_REBUILD_INTERVAL_NANOS) {
            rebuilding.set(false);
            return;
        }
        everRebuilt = true;
        lastRebuildStart = now;
        try {
            executor.execute(this::rebuild);
        } catch (RuntimeException e) {
            rebuilding.set(false);
            throw e;
        }
    }

    private void rebuild() {
        long start = System.nanoTime();
        try {
            TlshCorpusSnapshot snapshot = loader.get();
            current.set(snapshot);
            log.info("TLSH 메모리 corpus 구축: token={} size={} took={}ms", snapshot.token(), snapshot.size(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        } catch (RuntimeException e) {
            // DB 경로가 계속 답하므로 판정은 막히지 않는다(fail-close는 DB 경로가 지킨다). 다음 요청이 다시 시도한다.
            log.warn("TLSH 메모리 corpus 구축 실패", e);
        } finally {
            rebuilding.set(false);
        }
    }

    private static ExecutorService singleThread() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "tlsh-memory-corpus");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void destroy() {
        if (executor instanceof ExecutorService service) {
            service.shutdownNow();
        }
    }
}
