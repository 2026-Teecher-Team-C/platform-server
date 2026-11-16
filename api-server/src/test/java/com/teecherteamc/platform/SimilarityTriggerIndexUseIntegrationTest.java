package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V10 트리거가 CHAR(64) 열을 TEXT 변수와 비교하면 인덱스를 못 타고 표 전체를 읽는다. 지문 81만 건이면 목록 쓰기
 * 한 건마다 81만 행을 읽게 된다. 같은 트랜잭션의 pg_stat_xact_user_tables로 순차 스캔이 없는지 본다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SimilarityTriggerIndexUseIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static String randomSha256() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }

    private long seqScans(String table) {
        Long value = jdbc.queryForObject(
                "SELECT coalesce(seq_scan, 0) FROM pg_stat_xact_user_tables WHERE relname = ?", Long.class, table);
        return value == null ? 0 : value;
    }

    @Test
    void 블랙리스트_쓰기는_지문_표를_전체_스캔하지_않는다() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            long before = seqScans("file_fingerprints");
            jdbc.update("INSERT INTO hash_blacklist (hash_value, reason, source) VALUES (?, 'scan test', 'IMPORT')",
                    randomSha256());
            assertThat(seqScans("file_fingerprints")).isEqualTo(before);
            status.setRollbackOnly();
        });
    }

    @Test
    void 지문_쓰기는_유사도_캐시를_전체_스캔하지_않는다() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            long before = seqScans("similarity_cache");
            jdbc.update("""
                    INSERT INTO file_fingerprints (sha256, tlsh, calculation_status, algorithm_version, provenance)
                    VALUES (?, NULL, 'TOO_SHORT', 'test', 'VERIFIED_UPLOAD')
                    """, randomSha256());
            jdbc.update("""
                    INSERT INTO file_fingerprints (sha256, tlsh, calculation_status, algorithm_version, provenance)
                    VALUES (?, ?, 'CALCULATED', 'test', 'VERIFIED_UPLOAD')
                    """, randomSha256(),
                    "T158E4396ABD5298A0C4D455BAB75DC2EC330793B9D3EAB207CD05CB24A9CF9894D3D381");
            assertThat(seqScans("similarity_cache")).isEqualTo(before);
            status.setRollbackOnly();
        });
    }
}
