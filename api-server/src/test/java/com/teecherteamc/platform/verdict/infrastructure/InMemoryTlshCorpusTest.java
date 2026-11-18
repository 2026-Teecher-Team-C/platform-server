package com.teecherteamc.platform.verdict.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 스냅샷은 요청 트랜잭션의 토큰과 같을 때만 쓴다. 다르면 비우고(DB 경로) 재구축을 한 번만, 간격을 두고 요청한다. */
class InMemoryTlshCorpusTest {

    private final List<Runnable> queued = new ArrayList<>();
    private final AtomicLong now = new AtomicLong(0);
    private final AtomicInteger loads = new AtomicInteger();
    private final AtomicReference<String> dbToken = new AtomicReference<>("t1");
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final InMemoryTlshCorpus corpus = new InMemoryTlshCorpus(() -> {
        loads.incrementAndGet();
        return TlshCorpusSnapshot.builder(dbToken.get()).build();
    }, queued::add, now::get, registry);

    @Test
    void 스냅샷이_없으면_비우고_재구축을_한_번만_요청한다() {
        assertThat(corpus.snapshotFor("t1")).isEmpty();
        assertThat(corpus.snapshotFor("t1")).isEmpty();
        assertThat(queued).hasSize(1);

        runQueued();

        assertThat(loads.get()).isEqualTo(1);
        assertThat(corpus.snapshotFor("t1")).get().extracting(TlshCorpusSnapshot::token).isEqualTo("t1");
        assertThat(registry.get("verdict.similarity.memory").tag("result", "hit").counter().count()).isEqualTo(1);
        assertThat(registry.get("verdict.similarity.memory").tag("result", "stale").counter().count()).isEqualTo(2);
    }

    @Test
    void 토큰이_바뀌면_DB_경로로_보내고_최소_간격_뒤에만_다시_만든다() {
        corpus.snapshotFor("t1");
        runQueued();
        dbToken.set("t2");

        now.addAndGet(InMemoryTlshCorpus.MIN_REBUILD_INTERVAL_NANOS - 1);
        assertThat(corpus.snapshotFor("t2")).isEmpty();
        assertThat(queued).isEmpty();

        now.addAndGet(1);
        assertThat(corpus.snapshotFor("t2")).isEmpty();
        assertThat(queued).hasSize(1);
        runQueued();
        assertThat(corpus.snapshotFor("t2")).isPresent();
        assertThat(corpus.snapshotFor("t1")).isEmpty();
    }

    @Test
    void 재구축이_실패해도_다음_요청이_다시_시도한다() {
        InMemoryTlshCorpus failing = new InMemoryTlshCorpus(() -> {
            loads.incrementAndGet();
            throw new IllegalStateException("db down");
        }, queued::add, now::get, registry);
        failing.snapshotFor("t1");
        runQueued();
        now.addAndGet(InMemoryTlshCorpus.MIN_REBUILD_INTERVAL_NANOS);
        failing.snapshotFor("t1");
        runQueued();
        assertThat(loads.get()).isEqualTo(2);
        assertThat(failing.snapshotFor("t1")).isEmpty();
    }

    private void runQueued() {
        List<Runnable> tasks = new ArrayList<>(queued);
        queued.clear();
        tasks.forEach(Runnable::run);
    }
}
