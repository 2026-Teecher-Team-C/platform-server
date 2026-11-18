package com.teecherteamc.platform.verdict.infrastructure;

import com.trendmicro.tlsh.Tlsh;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** 토큰은 eligibility, revision, 자연 만료, 정책을 모두 포함한다. 후보 수 상한과 캐시 예산은 별개다. */
@Component
public class ParsedSimilarityCorpus {
    private static final int MAX_ENTRIES = 8192;
    private static final int MAX_TOKENS = 4;
    private final LinkedHashMap<String, List<Candidate>> snapshots = new LinkedHashMap<>(8, 0.75f, true);
    private int entries;
    /** 토큰별 진행 중인 첫 읽기(single-flight). this로 보호한다. */
    private final Map<String, CompletableFuture<List<Candidate>>> loading = new HashMap<>();
    private final Counter hits;
    private final Counter misses;
    /** 마지막으로 비교한 후보 수. 상한(similarity.corpus.limit)이 걸리면 그 수다. */
    private final AtomicLong size = new AtomicLong();

    public ParsedSimilarityCorpus(MeterRegistry registry) {
        this.hits = Counter.builder("verdict.similarity.cache.hits").register(registry);
        this.misses = Counter.builder("verdict.similarity.cache.misses").register(registry);
        Gauge.builder("verdict.similarity.corpus.size", size, AtomicLong::get).register(registry);
    }
    public record Candidate(String sha, String encoded, Tlsh parsed) {}

    /**
     * 호출자는 corpus 공유 락을 커밋까지 유지한다. 첫 miss는 한 번만 파싱하고 완성된 스냅샷만 게시한다.
     * 예산을 넘는 corpus도 끝까지 방문하되 캐시에 담지 않는다. DB cursor 외 추가 메모리는 예산 이내다.
     * 모니터(this)는 맵 회계만 짧게 보호한다. 읽기·파싱·방문은 락 밖에서 하므로 다른 토큰과 캐시 적중은 서로 막지 않는다
     * (2026-11-18 운영 부하 시험: 전역 synchronized로 비교가 하나씩만 돌아 처리량이 0.5건/s에 묶였다).
     * 같은 토큰의 동시 miss는 첫 요청의 결과를 기다린다. 첫 요청이 예산을 넘거나 실패하면 대기자는 곧바로 직접 읽는다.
     */
    public void visit(String token, Consumer<Consumer<Candidate>> load, Consumer<Candidate> visit) {
        List<Candidate> snapshot;
        CompletableFuture<List<Candidate>> inFlight;
        CompletableFuture<List<Candidate>> mine = null;
        synchronized (this) {
            snapshot = snapshots.get(token);
            inFlight = snapshot == null ? loading.get(token) : null;
            if (snapshot == null && inFlight == null) {
                mine = new CompletableFuture<>();
                loading.put(token, mine);
            }
        }
        if (inFlight != null) snapshot = inFlight.join();
        if (snapshot != null) {
            hits.increment();
            size.set(snapshot.size());
            snapshot.forEach(visit);
            return;
        }
        misses.increment();
        load(token, load, visit, mine);
    }

    /** mine이 null이면 대기하다 넘어온 요청이다. 게시는 하되 single-flight 자리는 이미 비어 있다. */
    private void load(String token, Consumer<Consumer<Candidate>> load, Consumer<Candidate> visit,
            CompletableFuture<List<Candidate>> mine) {
        long[] visited = {0};
        List<Candidate> collected = new ArrayList<>();
        boolean[] overflow = {false};
        try {
            load.accept(candidate -> {
                visited[0]++;
                visit.accept(candidate);
                if (!overflow[0]) {
                    if (collected.size() == MAX_ENTRIES) {
                        collected.clear();
                        overflow[0] = true;
                        release(token, mine, null);
                    } else {
                        collected.add(candidate);
                    }
                }
            });
        } catch (RuntimeException | Error e) {
            release(token, mine, null);
            throw e;
        }
        size.set(visited[0]);
        if (overflow[0]) return;
        List<Candidate> complete = List.copyOf(collected);
        synchronized (this) {
            if (!snapshots.containsKey(token)) {
                while (!snapshots.isEmpty()
                        && (snapshots.size() >= MAX_TOKENS || entries + complete.size() > MAX_ENTRIES)) {
                    var iterator = snapshots.entrySet().iterator();
                    entries -= iterator.next().getValue().size();
                    iterator.remove();
                }
                snapshots.put(token, complete);
                entries += complete.size();
            }
        }
        release(token, mine, complete);
    }

    /** 대기자를 깨운다. null은 "직접 읽어라"(예산 초과·실패)다. 반쪽 목록은 절대 넘기지 않는다. */
    private void release(String token, CompletableFuture<List<Candidate>> mine, List<Candidate> result) {
        if (mine == null || mine.isDone()) return;
        synchronized (this) {
            loading.remove(token, mine);
        }
        mine.complete(result);
    }
}
