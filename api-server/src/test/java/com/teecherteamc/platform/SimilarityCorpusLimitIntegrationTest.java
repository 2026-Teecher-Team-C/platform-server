package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.teecherteamc.platform.verdict.infrastructure.JdbcSimilarityDecisionStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/** similarity.corpus.limit: 규모별 곡선(시나리오 B)용 비교 대상 상한. 양수면 그 수만 비교한다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "similarity.corpus.limit=2")
@Transactional
class SimilarityCorpusLimitIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JdbcSimilarityDecisionStore store;

    @Autowired
    private MeterRegistry registry;

    @Test
    void 상한이_있으면_그_수만큼만_비교한다() {
        String salt = "limit-" + System.nanoTime();
        jdbc.update("INSERT INTO hash_blacklist(hash_value, reason) "
                + "SELECT repeat(md5(? || i::text), 2), ? FROM generate_series(1, 5) i", salt, salt);
        jdbc.update("INSERT INTO file_fingerprints(sha256, tlsh, calculation_status, algorithm_version, provenance) "
                + "SELECT hash_value, ?, 'CALCULATED', 'limit', 'VERIFIED_UPLOAD' FROM hash_blacklist WHERE reason=?",
                FingerprintImportRestIntegrationTest.TLSH, salt);

        store.compareTlsh(FingerprintImportRestIntegrationTest.TLSH);

        assertThat(registry.get("verdict.similarity.corpus.size").gauge().value()).isEqualTo(2);
    }
}
