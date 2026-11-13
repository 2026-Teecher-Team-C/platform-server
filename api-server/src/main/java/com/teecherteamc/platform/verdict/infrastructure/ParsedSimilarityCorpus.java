package com.teecherteamc.platform.verdict.infrastructure;

import com.trendmicro.tlsh.Tlsh;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** 토큰은 eligibility, revision, 자연 만료, 정책을 모두 포함한다. 후보 수 상한과 캐시 예산은 별개다. */
@Component
public class ParsedSimilarityCorpus {
    private static final int MAX_ENTRIES = 8192;
    private static final int MAX_TOKENS = 4;
    private final LinkedHashMap<String, List<Candidate>> snapshots = new LinkedHashMap<>(8, 0.75f, true);
    private int entries;
    public record Candidate(String sha, String encoded, Tlsh parsed) {}

    /**
     * 호출자는 corpus 공유 락을 커밋까지 유지한다. 첫 miss는 한 번만 파싱하고 완성된 스냅샷만 게시한다.
     * 예산을 넘는 corpus도 끝까지 방문하되 캐시에 담지 않는다. DB cursor 외 추가 메모리는 예산 이내다.
     * synchronized는 miss single-flight와 LRU 회계를 함께 보호, 다른 토큰의 반쪽 목록은 절대 노출하지 않는다.
     */
    public synchronized void visit(String token, Consumer<Consumer<Candidate>> load, Consumer<Candidate> visit) {
        List<Candidate> cached = snapshots.get(token);
        if (cached != null) {
            cached.forEach(visit);
            return;
        }
        List<Candidate> collected = new ArrayList<>();
        boolean[] overflow = {false};
        load.accept(candidate -> {
            visit.accept(candidate);
            if (!overflow[0]) {
                if (collected.size() == MAX_ENTRIES) { collected.clear(); overflow[0] = true; }
                else collected.add(candidate);
            }
        });
        if (overflow[0]) return;
        while (!snapshots.isEmpty() && (snapshots.size() >= MAX_TOKENS || entries + collected.size() > MAX_ENTRIES)) {
            var iterator = snapshots.entrySet().iterator();
            entries -= iterator.next().getValue().size();
            iterator.remove();
        }
        List<Candidate> complete = List.copyOf(collected);
        snapshots.put(token, complete);
        entries += complete.size();
    }
}
