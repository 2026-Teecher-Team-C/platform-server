package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * sha256 컬럼은 VARCHAR(64)여야 한다(V18). CHAR(64)이면 hash_blacklist.hash_value(VARCHAR) 등과 비교할 때
 * 한쪽이 bpchar로 바뀌어 인덱스를 못 쓴다(V15·V17에서 겪은 순차 스캔).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class Sha256VarcharSchemaIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void public_스키마에_character_타입_컬럼이_없다() {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT table_name || '.' || column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND data_type = 'character'", String.class);
        assertThat(columns).isEmpty();
    }

    @Test
    void 유사도_트리거_함수에_bpchar_캐스트가_없다() {
        String source = jdbcTemplate.queryForObject(
                "SELECT prosrc FROM pg_proc WHERE proname = 'revise_similarity_corpus'", String.class);
        assertThat(source).doesNotContain("bpchar");
    }
}
