package com.teecherteamc.platform.verdict.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import com.trendmicro.tlsh.Tlsh;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ParsedSimilarityCorpusTest {
    private final ParsedSimilarityCorpus cache = new ParsedSimilarityCorpus(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
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

    // 운영 부하 시험(2026-11-18): 전역 synchronized라 동시 요청을 늘려도 비교가 하나씩만 돌았다(처리량 0.5건/s 고정).
    @Test
    void different_tokens_load_concurrently() {
        java.util.concurrent.CyclicBarrier bothLoading = new java.util.concurrent.CyclicBarrier(2);
        CompletableFuture.allOf(java.util.stream.Stream.of("a", "b").map(token -> CompletableFuture.runAsync(() ->
            cache.visit(token, accept -> { await(bothLoading); accept.accept(candidate); }, c -> {})
        )).toArray(CompletableFuture[]::new)).orTimeout(10, java.util.concurrent.TimeUnit.SECONDS).join();
    }

    @Test
    void cached_snapshot_is_visited_without_blocking_other_loads() {
        cache.visit("hit", accept -> accept.accept(candidate), c -> {});
        java.util.concurrent.CyclicBarrier both = new java.util.concurrent.CyclicBarrier(2);
        CompletableFuture.allOf(
            CompletableFuture.runAsync(() -> cache.visit("hit", accept -> {}, c -> await(both))),
            CompletableFuture.runAsync(() -> cache.visit("miss", accept -> { await(both); accept.accept(candidate); }, c -> {}))
        ).orTimeout(10, java.util.concurrent.TimeUnit.SECONDS).join();
    }

    /** 캐시에 못 담는 크기라는 게 드러나면 같은 토큰 대기자도 바로 자기 읽기를 시작한다(끝까지 기다렸다 다시 읽지 않는다). */
    @Test
    void oversized_same_token_waiter_starts_its_own_load_before_first_finishes() {
        java.util.concurrent.CyclicBarrier bothLoading = new java.util.concurrent.CyclicBarrier(2);
        AtomicInteger visits = new AtomicInteger();
        CompletableFuture.allOf(java.util.stream.IntStream.range(0, 2).mapToObj(i -> CompletableFuture.runAsync(() ->
            cache.visit("oversized", accept -> {
                for (int n = 0; n < 10000; n++) { accept.accept(candidate); if (n == 9000) await(bothLoading); }
            }, c -> visits.incrementAndGet())
        )).toArray(CompletableFuture[]::new)).orTimeout(10, java.util.concurrent.TimeUnit.SECONDS).join();
        assertThat(visits.get()).isEqualTo(20000);
    }

    private static void await(java.util.concurrent.CyclicBarrier barrier) {
        try { barrier.await(5, java.util.concurrent.TimeUnit.SECONDS); }
        catch (Exception e) { throw new IllegalStateException("다른 방문이 동시에 진행되지 못했다", e); }
    }
}
