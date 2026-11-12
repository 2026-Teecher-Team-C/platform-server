package com.teecherteamc.platform.verdict.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import com.trendmicro.tlsh.Tlsh;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ParsedSimilarityCorpusTest {
    private final ParsedSimilarityCorpus cache = new ParsedSimilarityCorpus();
    private final ParsedSimilarityCorpus.Candidate candidate = new ParsedSimilarityCorpus.Candidate("a".repeat(64), "unused", null);

    @Test
    void concurrent_same_token_loads_one_complete_snapshot() {
        AtomicInteger loads = new AtomicInteger();
        AtomicInteger visits = new AtomicInteger();
        CompletableFuture.allOf(java.util.stream.IntStream.range(0, 16).mapToObj(i -> CompletableFuture.runAsync(() ->
            cache.visit("same", accept -> { loads.incrementAndGet(); for (int n=0; n<1000; n++) accept.accept(candidate); }, c -> visits.incrementAndGet())
        )).toArray(CompletableFuture[]::new)).join();
        assertThat(loads.get()).isEqualTo(1);
        assertThat(visits.get()).isEqualTo(16000);
    }

    @Test
    void oversized_corpus_is_fully_visited_and_never_cached() {
        AtomicInteger loads = new AtomicInteger();
        AtomicInteger visits = new AtomicInteger();
        for (int i=0; i<2; i++) cache.visit("oversized", accept -> {
            loads.incrementAndGet(); for (int n=0; n<10000; n++) accept.accept(candidate);
        }, c -> visits.incrementAndGet());
        assertThat(loads.get()).isEqualTo(2);
        assertThat(visits.get()).isEqualTo(20000);
    }

    @Test
    void failed_load_does_not_publish_partial_snapshot() {
        try { cache.visit("failed", accept -> { accept.accept(candidate); throw new IllegalStateException("read failed"); }, c -> {}); }
        catch (IllegalStateException expected) { /* DB 실패를 빈 corpus/ALLOW로 바꾸지 않는다. */ }
        AtomicInteger loads = new AtomicInteger();
        cache.visit("failed", accept -> { loads.incrementAndGet(); accept.accept(candidate); }, c -> {});
        assertThat(loads.get()).isEqualTo(1);
    }
}
