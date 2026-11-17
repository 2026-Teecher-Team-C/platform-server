package com.teecherteamc.platform.dashboard.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.teecherteamc.platform.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대시보드 쿼리가 hash_blacklist를 PK(hash_type, hash_value) 인덱스로 찾는지 본다.
 * download_events.sha256(char(64))과 hash_value(varchar)를 그냥 비교하면 hash_value 쪽이 bpchar로
 * 바뀌어 인덱스를 못 쓴다. 운영 블랙리스트 117만 행에서 쿼리 하나가 20초 걸려 콘솔이 연결 실패를 냈다(2026-11-17).
 * 빈 테이블에서는 플래너가 아무 방식이나 골라서, 블랙리스트를 채우고 통계를 만든 뒤 순차 스캔·해시·머지 조인·
 * Materialize를 끈다. 그러면 남는 길은 행마다 hash_value로 인덱스를 찾는 Nested Loop뿐이고, 조인 조건이
 * 인덱스를 못 쓰는 형태면 PK를 hash_type 조건만으로 전부 훑는 계획이 나온다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class HashBlacklistIndexUseIntegrationTest {

    private static final Pattern HASH_VALUE_INDEX_LOOKUP = Pattern.compile(
            "Index (Only )?Scan using pk_hash_blacklist[^\\n]*\\n\\s*Index Cond: [^\\n]*hash_value");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 블랙리스트를_채우고_통계를_만든다() {
        jdbcTemplate.update(
                """
                INSERT INTO hash_blacklist (hash_type, hash_value, reason, source)
                SELECT 'SHA256', encode(sha256(i::text::bytea), 'hex'), 'index-use test', 'IMPORT'
                FROM generate_series(1, 1000) AS i
                """);
        jdbcTemplate.execute("ANALYZE hash_blacklist");
    }

    private String plan(String sql, Object... args) {
        for (String setting : List.of("enable_seqscan", "enable_hashjoin", "enable_mergejoin", "enable_material")) {
            jdbcTemplate.execute("SET LOCAL " + setting + " = off");
        }
        List<String> lines = jdbcTemplate.queryForList("EXPLAIN " + sql, String.class, args);
        return String.join("\n", lines);
    }

    @Test
    void 위협_뷰는_블랙리스트를_hash_value_인덱스로_찾는다() {
        assertThat(plan("SELECT * FROM download_event_threats"))
                .containsPattern(HASH_VALUE_INDEX_LOOKUP);
    }

    @Test
    void 위협별_현황_쿼리는_블랙리스트를_hash_value_인덱스로_찾는다() {
        Timestamp now = Timestamp.from(Instant.now());
        assertThat(plan(DashboardWidgetsPortImpl.THREATS_SQL, now, now, 10))
                .containsPattern(HASH_VALUE_INDEX_LOOKUP);
    }
}
