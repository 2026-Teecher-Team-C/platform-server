package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** 무관한 목록 쓰기가 TLSH 캐시 전체를 무효화하지 않는 실제 DB 계약. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SimilarityCorpusReviewIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired com.teecherteamc.platform.verdict.infrastructure.JdbcSimilarityDecisionStore store;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired com.teecherteamc.platform.lookup.application.CheckHashUseCase checkHash;

    @Test
    void unrelated_list_changes_leave_corpus_token_unchanged() {
        String sha = UUID.randomUUID().toString().replace("-", "").repeat(2);
        String token = jdbc.queryForObject("SELECT token FROM current_similarity_corpus_token", String.class);
        UUID admin = UUID.randomUUID();
        jdbc.update("INSERT INTO admin_users(admin_id, username, password_hash) VALUES (?, ?, 'unused')", admin, "review-" + admin);
        try {
            jdbc.update("INSERT INTO hash_blacklist(hash_value, reason) VALUES (?, 'review fixture')", sha);
            jdbc.update("INSERT INTO hash_whitelist(hash_value, reason, added_by) VALUES (?, 'review fixture', ?)", sha, admin);
            assertThat(jdbc.queryForObject("SELECT token FROM current_similarity_corpus_token", String.class)).isEqualTo(token);
        } finally {
            jdbc.update("DELETE FROM hash_whitelist WHERE hash_value=?", sha);
            jdbc.update("DELETE FROM hash_blacklist WHERE hash_value=?", sha);
        }
    }
    @Test
    void ordinary_fingerprint_upload_does_not_wait_for_global_shared_reader() throws Exception {
        String sha = UUID.randomUUID().toString().replace("-", "").repeat(2);
        try (var reader = dataSource.getConnection()) {
            reader.setAutoCommit(false);
            reader.createStatement().execute("SELECT pg_advisory_xact_lock_shared(74190321)");
            var task = java.util.concurrent.CompletableFuture.runAsync(() -> jdbc.update(
                    "INSERT INTO file_fingerprints(sha256, tlsh, calculation_status, algorithm_version, provenance) "
                    + "VALUES (?, ?, 'CALCULATED', 'review', 'VERIFIED_UPLOAD')", sha, FingerprintImportRestIntegrationTest.TLSH));
            try { task.get(2, java.util.concurrent.TimeUnit.SECONDS); }
            finally { reader.rollback(); task.get(5, java.util.concurrent.TimeUnit.SECONDS); }
        }
    }

    @Test
    void check_hash_returns_unknown_with_bounded_lock_wait() throws Exception {
        try (var writer = dataSource.getConnection()) {
            writer.setAutoCommit(false);
            writer.createStatement().execute("SELECT pg_advisory_xact_lock(74190321)");
            long start = System.nanoTime();
            var result = java.util.concurrent.CompletableFuture.supplyAsync(() -> checkHash.checkHash("ab".repeat(32)));
            try {
                assertThat(result.get(2, java.util.concurrent.TimeUnit.SECONDS).decision()).isEqualTo("UNKNOWN");
                assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1500);
            } finally { writer.rollback(); result.get(5, java.util.concurrent.TimeUnit.SECONDS); }
        }
    }

    @Test
    void unchanged_corpus_parses_baseline_only_once_across_comparisons() {
        String sample = UUID.randomUUID().toString().replace("-", "").repeat(2);
        String sha = UUID.randomUUID().toString().replace("-", "").repeat(2);
        String baseline = FingerprintImportRestIntegrationTest.TLSH;
        String input = baseline.substring(0, baseline.length()-1) + (baseline.endsWith("A") ? "B" : "A");
        jdbc.update("INSERT INTO hash_blacklist(hash_value, reason) VALUES (?, 'review fixture')", sample);
        for (String hash : java.util.List.of(sample, sha)) jdbc.update(
                "INSERT INTO file_fingerprints(sha256, tlsh, calculation_status, algorithm_version, provenance) VALUES (?, ?, 'CALCULATED', 'review', 'VERIFIED_UPLOAD')",
                hash, hash.equals(sample) ? baseline : input);
        try (var parser = org.mockito.Mockito.mockStatic(com.trendmicro.tlsh.Tlsh.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
            for (int i=0; i<2; i++) tx.executeWithoutResult(status -> { store.lockCorpus(); store.compare(sha); });
            parser.verify(() -> com.trendmicro.tlsh.Tlsh.fromTlshStr(baseline), org.mockito.Mockito.times(1));
        } finally { jdbc.update("DELETE FROM hash_blacklist WHERE hash_value=?", sample); }
    }

    @Test
    void new_baseline_insert_waits_for_uncommitted_fingerprint_then_revises_corpus() throws Exception {
        String sha = UUID.randomUUID().toString().replace("-", "").repeat(2);
        String before = jdbc.queryForObject("SELECT token FROM current_similarity_corpus_token", String.class);
        try (var upload = dataSource.getConnection()) {
            upload.setAutoCommit(false);
            try (var statement = upload.prepareStatement("INSERT INTO file_fingerprints(sha256, tlsh, calculation_status, algorithm_version, provenance) VALUES (?, ?, 'CALCULATED', 'review', 'VERIFIED_UPLOAD')")) {
                statement.setString(1, sha); statement.setString(2, FingerprintImportRestIntegrationTest.TLSH); statement.executeUpdate();
            }
            var listWrite = java.util.concurrent.CompletableFuture.runAsync(() -> jdbc.update(
                    "INSERT INTO hash_blacklist(hash_value, reason) VALUES (?, 'review race')", sha));
            // 실제 pg_locks 대기로 동기화한다. sleep 타이밍을 성공의 근거로 삼지 않는다.
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < deadline && jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted", Integer.class)==0) Thread.onSpinWait();
            try {
                assertThat(listWrite.isDone()).isFalse();
                upload.commit();
                listWrite.get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(jdbc.queryForObject("SELECT token FROM current_similarity_corpus_token", String.class)).isNotEqualTo(before);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM active_malicious_fingerprints WHERE sha256=?", Integer.class, sha)).isEqualTo(1);
            } finally { upload.rollback(); listWrite.get(5, java.util.concurrent.TimeUnit.SECONDS); jdbc.update("DELETE FROM hash_blacklist WHERE hash_value=?", sha); }
        }
    }

    @Test
    void shared_lock_key_contract_matches_sql_and_rejects_unknown_scope() {
        assertThat(jdbc.queryForObject("SELECT similarity_lock_key('corpus')", Integer.class)).isEqualTo(74190321);
        assertThat(jdbc.queryForObject("SELECT similarity_lock_key('file')", Integer.class)).isEqualTo(74190322);
        assertThat(jdbc.queryForObject("SELECT similarity_lock_key('event')", Integer.class)).isEqualTo(74190323);
        assertThat(jdbc.queryForObject("SELECT similarity_lock_key('eligibility')", Integer.class)).isEqualTo(74190324);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.queryForObject("SELECT similarity_lock_key('typo')", Integer.class))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void thousand_candidate_db_probe_measures_complete_comparison_and_warm_reuse() {
        String salt = UUID.randomUUID().toString();
        String sha = UUID.randomUUID().toString().replace("-", "").repeat(2);
        jdbc.update("INSERT INTO file_fingerprints(sha256, tlsh, calculation_status, algorithm_version, provenance) VALUES (?, ?, 'CALCULATED', 'review', 'VERIFIED_UPLOAD')", sha, FingerprintImportRestIntegrationTest.TLSH);
        try {
            jdbc.update("INSERT INTO hash_blacklist(hash_value, reason) SELECT repeat(md5(? || i::text),2), ? FROM generate_series(1,1000) i", salt, salt);
            jdbc.update("INSERT INTO file_fingerprints(sha256, tlsh, calculation_status, algorithm_version, provenance) SELECT hash_value, ?, 'CALCULATED', 'review', 'VERIFIED_UPLOAD' FROM hash_blacklist WHERE reason=?", FingerprintImportRestIntegrationTest.TLSH, salt);
            var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
            long[] elapsed = new long[2];
            for (int run=0; run<2; run++) {
                long start = System.nanoTime();
                var assessment = tx.execute(status -> { store.lockCorpus(); return store.compare(sha); });
                elapsed[run] = System.nanoTime()-start;
                assertThat(assessment.distance()).isEqualTo(0);
            }
            System.out.printf("PR92_CORPUS_PROBE candidates=1000 cold_ms=%.3f warm_ms=%.3f%n", elapsed[0]/1e6, elapsed[1]/1e6);
        } finally { jdbc.update("DELETE FROM hash_blacklist WHERE reason=?", salt); }
    }

}
