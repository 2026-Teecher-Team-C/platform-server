package com.teecherteamc.platform.verdict.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** TLSH corpus 캐시 적중·크기 지표(고도화 테스트 1-1). 상한 8,192를 넘는 corpus는 매번 miss다. */
class ParsedSimilarityCorpusMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ParsedSimilarityCorpus corpus = new ParsedSimilarityCorpus(registry);
    private final ParsedSimilarityCorpus.Candidate candidate =
            new ParsedSimilarityCorpus.Candidate("a".repeat(64), "unused", null);

    @Test
    void 같은_토큰_두번째_방문은_적중이고_크기는_후보_수다() {
        visit("t1", 3);
        visit("t1", 3);

        assertThat(count("verdict.similarity.cache.misses")).isEqualTo(1);
        assertThat(count("verdict.similarity.cache.hits")).isEqualTo(1);
        assertThat(registry.get("verdict.similarity.corpus.size").gauge().value()).isEqualTo(3);
    }

    @Test
    void 캐시_상한을_넘는_corpus는_매번_miss이고_크기는_전체다() {
        visit("big", 8193);
        visit("big", 8193);

        assertThat(count("verdict.similarity.cache.misses")).isEqualTo(2);
        assertThat(count("verdict.similarity.cache.hits")).isZero();
        assertThat(registry.get("verdict.similarity.corpus.size").gauge().value()).isEqualTo(8193);
    }

    private void visit(String token, int size) {
        corpus.visit(token, accept -> {
            for (int i = 0; i < size; i++) {
                accept.accept(candidate);
            }
        }, c -> { });
    }

    private double count(String name) {
        return registry.get(name).counter().count();
    }
}
