package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.teecherteamc.platform.verdict.infrastructure.TlshLValue;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** V19: SQL tlsh_lvalue()가 Java와 같은 값을 내고, corpus 조회가 L-value 부분 인덱스를 쓴다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class TlshLValueIndexIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void SQL_tlsh_lvalue는_Java와_같다() {
        for (String tlsh : List.of(FingerprintImportRestIntegrationTest.TLSH,
                "T1AB" + "F0" + "0".repeat(66), "T1AB" + "1E" + "0".repeat(66), "T1AB" + "FF" + "0".repeat(66))) {
            Integer sql = jdbc.queryForObject("SELECT tlsh_lvalue(?)", Integer.class, tlsh);
            assertThat(sql).as(tlsh).isEqualTo(TlshLValue.of(tlsh));
        }
    }

    @Test
    void corpus_조회는_L_value_부분_인덱스를_쓴다() {
        // 빈 표에서는 플래너가 아무 계획이나 골라(CI에서 실패) L-value가 고루 퍼진 지문을 넣고 통계를 만든다.
        jdbc.update("""
                INSERT INTO file_fingerprints(sha256, tlsh, calculation_status, algorithm_version, provenance)
                SELECT encode(sha256(('lvalue-index-' || i)::bytea), 'hex'),
                       'T1AB' || upper(lpad(to_hex(i % 256), 2, '0')) || upper(substr(repeat(md5(i::text), 3), 1, 66)),
                       'CALCULATED', 'lvalue-index', 'VERIFIED_UPLOAD'
                FROM generate_series(1, 2000) AS i
                """);
        // 운영처럼 지문마다 활성 블랙리스트가 있어야 플래너가 블랙리스트가 아니라 지문 쪽(L-value)부터 고른다.
        jdbc.update("""
                INSERT INTO hash_blacklist(hash_value, reason)
                SELECT sha256, 'lvalue-index' FROM file_fingerprints WHERE algorithm_version = 'lvalue-index'
                """);
        jdbc.execute("ANALYZE file_fingerprints");
        jdbc.execute("ANALYZE hash_blacklist");
        jdbc.execute("SET LOCAL enable_seqscan = off");
        String plan = String.join("\n", jdbc.queryForList(
                "EXPLAIN SELECT sha256, tlsh FROM active_malicious_fingerprints WHERE tlsh_lvalue(tlsh) = ANY(?)",
                String.class, (Object) new Integer[] {10, 11, 12}));
        assertThat(plan).contains("idx_fingerprints_tlsh_lvalue");
    }
}
