package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 이미 배포된 develop 이력 위에 미배포 TLSH만 추가되는지 실제 PostgreSQL로 검증한다. */
@Testcontainers
class DevelopMigrationUpgradeTest {
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    private Flyway migrations(String target) {
        var config = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration");
        if (target != null) config.target(target);
        return config.load();
    }

    @Test
    void developHistoryAndExistingUserSurviveTlshUpgradeWithoutRepair() throws Exception {
        // V6이 실제 VIEWER 행을 USER로 바꾸는 과정도 포함한다. fresh 최종 스키마만으로는 이 경로를 검증할 수 없다.
        migrations("5").migrate();
        try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            db.createStatement().executeUpdate("INSERT INTO admin_users(admin_id, username, password_hash, role) "
                    + "VALUES (gen_random_uuid(), 'existing-employee', 'unused', 'VIEWER')");
            migrations("7").migrate();
            migrations("7").validate();
            List<String> deployedHistory = history(db);
            assertThat(deployedHistory).hasSize(7);
            assertThat(scalar(db, "SELECT role FROM admin_users WHERE username='existing-employee'")).isEqualTo("USER");
            assertThat(scalar(db, "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' "
                    + "AND table_type='BASE TABLE' AND table_name <> 'flyway_schema_history'")).isEqualTo("18");
            String viewDefinition = scalar(db, "SELECT pg_get_viewdef('download_event_threats'::regclass, true)");

            var full = migrations(null);
            assertThat(full.migrate().migrationsExecuted).isEqualTo(5);
            // validate()가 현재 리소스의 체크섬과 배포 이력을 비교한다. repair/missing 무시 없이 검증한다.
            full.validate();
            assertThat(history(db).subList(0, 7)).isEqualTo(deployedHistory);
            assertThat(full.info().applied()).hasSize(12);
            assertThat(full.info().pending()).isEmpty();
            assertThat(java.util.Arrays.stream(full.info().all()).map(i -> i.getState().name()))
                    .containsOnly("SUCCESS");
            assertThat(scalar(db, "SELECT pg_get_viewdef('download_event_threats'::regclass, true)")).isNotEqualTo(viewDefinition);
            assertThat(scalar(db, "SELECT pg_get_viewdef('download_event_threats'::regclass, true)")).contains("SIMILARITY", "MEDIUM");
            assertThat(scalar(db, "SELECT role FROM admin_users WHERE username='existing-employee'")).isEqualTo("USER");
            assertThat(scalar(db, "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' "
                    + "AND table_type='BASE TABLE' AND table_name <> 'flyway_schema_history'")).isEqualTo("23");
            for (String table : List.of("file_fingerprints", "similarity_assessments", "similarity_cache",
                    "similarity_corpus_state", "event_decision_evidence", "download_event_acknowledgements")) {
                assertThat(scalar(db, "SELECT to_regclass('" + table + "')")).isEqualTo(table);
            }
        }
    }

    private static List<String> history(Connection db) throws Exception {
        List<String> rows = new ArrayList<>();
        try (var result = db.createStatement().executeQuery(
                "SELECT version || ':' || script || ':' || checksum FROM flyway_schema_history WHERE success ORDER BY installed_rank")) {
            while (result.next()) rows.add(result.getString(1));
        }
        return rows;
    }

    private static String scalar(Connection db, String sql) throws Exception {
        try (var result = db.createStatement().executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }
}
