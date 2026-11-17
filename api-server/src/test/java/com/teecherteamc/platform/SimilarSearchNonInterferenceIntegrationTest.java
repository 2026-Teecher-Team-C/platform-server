package com.teecherteamc.platform;

import static com.teecherteamc.platform.TlshFixtures.REFERENCE;
import static com.teecherteamc.platform.TlshFixtures.digestAt;
import static org.assertj.core.api.Assertions.assertThat;

import com.teecherteamc.platform.verdict.application.FindSimilarFiles;
import com.teecherteamc.platform.verdict.domain.FingerprintProvenance;
import com.teecherteamc.platform.verdict.domain.SimilarFiles;
import com.teecherteamc.platform.verdict.domain.SimilarFingerprintSearchPort;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 유사 파일 검색이 판정 경로를 방해하지 않는 실제 DB 계약(설계 G2-A, G7-A).
 * 판정(CheckHash, SubmitFile)과 블랙리스트 쓰기가 쓰는 corpus advisory 잠금 키는 74190321이다(V10).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SimilarSearchNonInterferenceIntegrationTest {

    @Autowired FindSimilarFiles service;
    @Autowired SimilarFingerprintSearchPort port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactions;

    private static final Set<FingerprintProvenance> VERIFIED = Set.of(FingerprintProvenance.VERIFIED_UPLOAD);

    @Test
    void 다른_연결이_corpus_배타_잠금을_쥐고_있어도_조회는_기다리지_않고_끝난다() throws Exception {
        try (var writer = dataSource.getConnection()) {
            writer.setAutoCommit(false);
            writer.createStatement().execute("SELECT pg_advisory_xact_lock(74190321)");
            var search = CompletableFuture.supplyAsync(() -> service.byTlsh(REFERENCE, 70, 10));
            try {
                SimilarFiles result = search.get(3, TimeUnit.SECONDS);

                assertThat(result.status()).isEqualTo(SimilarFiles.Status.OK);
            } finally {
                writer.rollback();
                search.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void 조회는_advisory_잠금을_하나도_잡지_않는다() {
        var tx = new TransactionTemplate(transactions);
        tx.setReadOnly(true);

        Integer held = tx.execute(status -> {
            port.scan(REFERENCE, VERIFIED, 70, (sha, distance) -> {});
            return jdbc.queryForObject(
                    "SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND pid = pg_backend_pid()", Integer.class);
        });

        assertThat(held).isZero();
    }

    @Test
    void 조회_트랜잭션이_열려_있어도_블랙리스트_쓰기는_막히지_않는다() throws Exception {
        String sha = UUID.randomUUID().toString().replace("-", "").repeat(2);
        var scanDone = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var tx = new TransactionTemplate(transactions);
        tx.setReadOnly(true);
        // 조회를 끝낸 뒤에도 트랜잭션을 열어 둔다. 조회가 잠금을 남겼다면 아래 쓰기가 막힌다.
        var reader = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            port.scan(REFERENCE, VERIFIED, 70, (s, d) -> {});
            scanDone.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        try {
            assertThat(scanDone.await(10, TimeUnit.SECONDS)).isTrue();
            // 블랙리스트 INSERT는 행 트리거가 corpus 배타 잠금을 얻는다(V10). 조회가 공유 잠금을 쥐고 있었다면 막힌다.
            var writer = CompletableFuture.runAsync(() -> jdbc.update(
                    "INSERT INTO hash_blacklist(hash_value, reason) VALUES (?, 'non-interference fixture')", sha));

            writer.get(3, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            reader.get(5, TimeUnit.SECONDS);
            jdbc.update("DELETE FROM hash_blacklist WHERE hash_value=?", sha);
        }
    }

    @Test
    void 조회_전후로_코퍼스_토큰이_바뀌지_않고_판정용_캐시를_채우지_않는다() {
        String sha = UUID.randomUUID().toString().replace("-", "").repeat(2);
        jdbc.update("INSERT INTO file_fingerprints(sha256, tlsh, calculation_status, algorithm_version, provenance) "
                + "VALUES (?, ?, 'CALCULATED', 'review', 'VERIFIED_UPLOAD')", sha, digestAt(10));
        try {
            String before = jdbc.queryForObject("SELECT token FROM current_similarity_corpus_token", String.class);
            long cacheBefore = jdbc.queryForObject("SELECT count(*) FROM similarity_cache", Long.class);

            service.byTlsh(REFERENCE, 70, 10);
            service.bySha256(sha, 70, 10);

            assertThat(jdbc.queryForObject("SELECT token FROM current_similarity_corpus_token", String.class))
                    .isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM similarity_cache", Long.class)).isEqualTo(cacheBefore);
        } finally {
            jdbc.update("DELETE FROM file_fingerprints WHERE sha256=?", sha);
        }
    }
}
