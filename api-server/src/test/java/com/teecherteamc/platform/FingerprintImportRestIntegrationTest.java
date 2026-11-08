package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfiguration.class, AdminApiClient.class})
class FingerprintImportRestIntegrationTest {
    static final String URL = "/api/admin/file-fingerprints/import";
    static final String TLSH = "T1A7D14D74F9407AE28FAE92B7CCFE6BA1509763248ED678CC9474B7C71593294FD00851";
    @Autowired TestRestTemplate rest;
    @Autowired AdminApiClient admin;
    @Autowired JdbcTemplate jdbc;

    @Test
    void admin_import_enriches_active_baseline_without_creating_a_verdict() {
        String sha = randomSha();
        seed(sha);
        var response = admin.postForEntity(URL, payload(sha, TLSH), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        var row = jdbc.queryForMap("SELECT * FROM file_fingerprints WHERE sha256 = ?", sha);
        assertThat(row.get("tlsh")).isEqualTo(TLSH);
        assertThat(row.get("provenance")).isEqualTo("ADMIN_IMPORT");
        assertThat(row.get("provenance_detail")).isEqualTo("internal-reference-test");
        assertThat(row.get("imported_by")).isEqualTo(admin.adminId());
        assertThat(count("file_verdicts", "sha256", sha)).isZero();
        assertThat(count("analyses", "sha256", sha)).isZero();
        assertThat(count("audit_logs", "target_id", sha)).isEqualTo(1);
    }

    @Test
    void viewer_cannot_import_even_with_a_valid_access_token() {
        String sha = randomSha();
        seed(sha);
        UUID actor = admin.adminId();
        jdbc.update("UPDATE admin_users SET role = 'VIEWER' WHERE admin_id = ?", actor);
        try {
            assertThat(admin.postForEntity(URL, payload(sha, TLSH), Map.class).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(count("file_fingerprints", "sha256", sha)).isZero();
        } finally {
            jdbc.update("UPDATE admin_users SET role = 'ADMIN' WHERE admin_id = ?", actor);
        }
    }

    @Test
    void candidates_require_current_active_sha_blacklist_and_exclude_effective_whitelist() {
        String sha = randomSha();
        seed(sha);
        admin.postForEntity(URL, payload(sha, TLSH), Map.class);
        assertThat(count("active_malicious_fingerprints", "sha256", sha)).isEqualTo(1);
        jdbc.update("UPDATE hash_blacklist SET is_active = false WHERE hash_value = ?", sha);
        assertThat(count("active_malicious_fingerprints", "sha256", sha)).isZero();
        jdbc.update("UPDATE hash_blacklist SET is_active = true WHERE hash_value = ?", sha);
        jdbc.update("INSERT INTO hash_whitelist(hash_value, reason, added_by) VALUES (?, 'test', ?)",
                sha, admin.adminId());
        assertThat(count("active_malicious_fingerprints", "sha256", sha)).isZero();
        jdbc.update("UPDATE hash_whitelist SET expires_at = now() - interval '1 second' WHERE hash_value = ?", sha);
        assertThat(count("active_malicious_fingerprints", "sha256", sha)).isEqualTo(1);
    }

    @Test
    void unauthenticated_request_is_401() {
        assertThat(rest.postForEntity(URL, payload(randomSha(), TLSH), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void malformed_sha_or_tlsh_or_missing_provenance_is_400_without_writes() {
        String sha = randomSha();
        seed(sha);
        for (var payload : java.util.List.of(
                payload("not-sha", TLSH), payload(sha, "TNULL"), payload(sha, TLSH.toLowerCase()),
                payload(" " + sha, TLSH), payload(sha, "T2" + TLSH.substring(2)),
                Map.of("sha256", sha, "tlsh", TLSH, "provenance", " "),
                Map.of("sha256", sha, "tlsh", TLSH, "provenance", "x".repeat(513)),
                Map.of("sha256", sha, "provenance", "test"))) {
            assertThat(admin.postForEntity(URL, payload, Map.class).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(count("file_fingerprints", "sha256", sha)).isZero();
        assertThat(count("audit_logs", "target_id", sha)).isZero();
    }

    @Test
    void missing_or_inactive_blacklist_is_409_and_does_not_create_baseline() {
        String sha = randomSha();
        assertThat(admin.postForEntity(URL, payload(sha, TLSH), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(count("hash_blacklist", "hash_value", sha)).isZero();
        seed(sha);
        jdbc.update("UPDATE hash_blacklist SET is_active = false WHERE hash_value = ?", sha);
        assertThat(admin.postForEntity(URL, payload(sha, TLSH), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(count("file_fingerprints", "sha256", sha)).isZero();
        assertThat(count("audit_logs", "target_id", sha)).isZero();
    }

    @Test
    void legacy_digest_is_canonicalized_and_verified_upload_cannot_be_overwritten() {
        String sha = randomSha();
        seed(sha);
        assertThat(admin.postForEntity(URL, payload(sha, TLSH.substring(2)), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForObject("SELECT tlsh FROM file_fingerprints WHERE sha256 = ?", String.class, sha))
                .isEqualTo(TLSH);
        jdbc.update("UPDATE file_fingerprints SET provenance = 'VERIFIED_UPLOAD', imported_by = NULL WHERE sha256 = ?", sha);
        assertThat(admin.postForEntity(URL, payload(sha, TLSH), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT provenance FROM file_fingerprints WHERE sha256 = ?", String.class, sha))
                .isEqualTo("VERIFIED_UPLOAD");
        assertThat(count("audit_logs", "target_id", sha)).isEqualTo(1);
    }

    void seed(String sha) {
        jdbc.update("INSERT INTO hash_blacklist(hash_value, reason) VALUES (?, 'test baseline')", sha);
    }
    int count(String table, String key, String value) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + key + " = ?", Integer.class, value);
    }
    static Map<String, String> payload(String sha, String tlsh) {
        return Map.of("sha256", sha, "tlsh", tlsh, "provenance", "internal-reference-test");
    }
    static String randomSha() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }
}
