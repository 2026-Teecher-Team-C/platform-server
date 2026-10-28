package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SchemaMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 마이그레이션이_17개_테이블을_만든다() {
        List<String> tables = jdbcTemplate.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                  AND table_name <> 'flyway_schema_history'
                """, String.class);

        assertThat(tables).containsExactlyInAnyOrder(
                "admin_users", "devices", "agents", "yara_rulesets", "yara_rules",
                "file_verdicts", "download_events", "analyses", "analysis_matches",
                "hash_blacklist", "hash_whitelist", "quarantine_files",
                "bypass_domains", "file_type_policies", "audit_logs",
                "agent_enrollment_tokens", "pending_quarantine_uploads");
    }

    @Test
    void 화이트리스트는_TLSH를_거부한다() {
        String adminId = "00000000-0000-0000-0000-000000000001";
        jdbcTemplate.update("INSERT INTO admin_users (admin_id, username, password_hash) VALUES (?::uuid, 'a', 'x')",
                adminId);

        Throwable thrown = catchThrowable(() -> jdbcTemplate.update("""
                INSERT INTO hash_whitelist (hash_type, hash_value, reason, added_by)
                VALUES ('TLSH', 'T1ABC', 'test', ?::uuid)
                """, adminId));

        assertThat(thrown).hasMessageContaining("ck_hash_whitelist_type");
    }

    @Test
    void V5_후_활성_룰셋은_v1_하나이고_룰_2개를_가진다() {
        List<Integer> active = jdbcTemplate.queryForList(
                "SELECT rulesets_version FROM yara_rulesets WHERE is_active", Integer.class);
        Integer ruleCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM yara_rules WHERE rulesets_version = 1", Integer.class);
        String source = jdbcTemplate.queryForObject(
                "SELECT source FROM yara_rulesets WHERE rulesets_version = 1", String.class);

        assertThat(active).containsExactly(1);
        assertThat(ruleCount).isEqualTo(2);
        assertThat(source)
                .contains("rule EICAR_Test_File")
                .contains("rule Teecher_Demo_Fake_Malware");
    }
}
