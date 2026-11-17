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
        jdbc.execute("SET LOCAL enable_seqscan = off");
        String plan = String.join("\n", jdbc.queryForList(
                "EXPLAIN SELECT sha256, tlsh FROM active_malicious_fingerprints WHERE tlsh_lvalue(tlsh) = ANY(?)",
                String.class, (Object) new Integer[] {10, 11, 12}));
        assertThat(plan).contains("idx_fingerprints_tlsh_lvalue");
    }
}
