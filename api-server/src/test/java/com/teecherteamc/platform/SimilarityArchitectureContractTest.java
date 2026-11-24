package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SimilarityArchitectureContractTest {
    @Test
    void application_uses_ports_not_sql() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/teecherteamc/platform/verdict/application/SimilarityDecisions.java"));
        assertThat(source).doesNotContain("JdbcTemplate", "SELECT ", "INSERT ", "UPDATE ", "DELETE ");
        assertThat(source).contains("WhitelistLookupPort", "BlacklistLookupPort", "SimilarityDecisionStore");
    }
    @Test
    void advisory_keys_have_one_sql_source_not_repeated_java_numbers() throws Exception {
        try (var files = Files.walk(Path.of("src/main/java/com/teecherteamc/platform"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                assertThat(Files.readString(file)).as(file.toString()).doesNotContain("74190321", "74190322", "74190323", "74190324");
            }
        }
        assertThat(Files.readString(Path.of("src/main/resources/db/migration/V10__similarity_corpus_coherence.sql")))
                .contains("similarity_lock_key", "IMMUTABLE", "STRICT");
    }

    /** 유사 파일 검색은 판정 경로(코퍼스 캐시, advisory 잠금)와 상태를 공유하지 않는다(설계 G2-A, G7-A). */
    @Test
    void similar_file_search_shares_no_cache_or_lock_with_the_judgement_path() throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/com/teecherteamc/platform/verdict/application/FindSimilarFiles.java"));
        String adapter = Files.readString(Path.of(
                "src/main/java/com/teecherteamc/platform/verdict/infrastructure/JdbcSimilarFingerprintSearch.java"));

        assertThat(service).doesNotContain("JdbcTemplate", "SELECT ", "INSERT ", "UPDATE ", "DELETE ")
                .doesNotContain("ParsedSimilarityCorpus", "SimilarityDecisions", "similarity_lock_key", "pg_advisory")
                .contains("SimilarFingerprintSearchPort");
        assertThat(adapter).doesNotContain("ParsedSimilarityCorpus", "similarity_lock_key", "pg_advisory",
                "current_similarity_corpus_token", "INSERT ", "UPDATE ", "DELETE ");
    }

}
