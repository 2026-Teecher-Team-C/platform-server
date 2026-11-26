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
    void 마이그레이션이_24개_테이블을_만든다() {
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
                "agent_enrollment_tokens", "pending_quarantine_uploads",
                "download_event_acknowledgements", "file_fingerprints",
                "similarity_assessments", "event_decision_evidence", "similarity_cache", "similarity_corpus_state",
                "appeals");
    }

    @Test
    void V22는_소명_근거_스냅숏과_화이트리스트_출처_소명_FK를_추가한다() {
        List<String> appealColumns = jdbcTemplate.queryForList("""
                SELECT column_name || ':' || data_type || ':' || character_maximum_length || ':' || is_nullable
                FROM information_schema.columns
                WHERE table_name = 'appeals' AND column_name IN ('evidence_kind', 'threat')
                """, String.class);
        Integer fk = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.table_constraints
                WHERE table_name = 'hash_whitelist' AND constraint_name = 'fk_hash_whitelist_appeal'
                  AND constraint_type = 'FOREIGN KEY'
                """, Integer.class);

        assertThat(appealColumns).containsExactlyInAnyOrder(
                "evidence_kind:character varying:16:YES", "threat:character varying:255:YES");
        assertThat(fk).isEqualTo(1);
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

    @Test
    void V6_후_역할_기본값은_USER이고_옛_이름_VIEWER는_거부한다() {
        String adminId = "00000000-0000-0000-0000-000000000002";
        jdbcTemplate.update(
                "INSERT INTO admin_users (admin_id, username, password_hash) VALUES (?::uuid, 'role-default', 'x')",
                adminId);

        String role = jdbcTemplate.queryForObject(
                "SELECT role FROM admin_users WHERE admin_id = ?::uuid", String.class, adminId);
        Throwable thrown = catchThrowable(() -> jdbcTemplate.update(
                "INSERT INTO admin_users (admin_id, username, password_hash, role) "
                        + "VALUES (gen_random_uuid(), 'role-viewer', 'x', 'VIEWER')"));

        assertThat(role).isEqualTo("USER");
        assertThat(thrown).hasMessageContaining("ck_admin_users_role");
    }
}
