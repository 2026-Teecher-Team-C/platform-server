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
import org.springframework.jdbc.core.JdbcTemplate;

@SuppressWarnings("unchecked")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfiguration.class, AdminApiClient.class})
class SampleDetailRestIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminApiClient admin;
    @Autowired TestRestTemplate rest;
    static String hash() { return UUID.randomUUID().toString().replace("-", "").repeat(2); }
    String url(String sha) { return "/api/admin/samples/" + sha; }

    @Test
    void authorizationAndLiteralFormatAreEnforced() {
        assertThat(rest.getForEntity(url(hash()), Map.class).getStatusCode().value()).isEqualTo(401);
        var headers = new org.springframework.http.HttpHeaders();
        headers.setBearerAuth("not-a-valid-token");
        assertThat(rest.exchange(url(hash()), org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(headers), Map.class).getStatusCode().value()).isEqualTo(401);
        for (String value : java.util.List.of("bad", "a".repeat(63), "g".repeat(64), "a".repeat(65), " " + hash(), hash() + " ")) {
            assertThat(admin.getForEntity(url(value), Map.class).getStatusCode().value()).as(value).isEqualTo(400);
        }
        assertThat(admin.getForEntity(url(hash()), Map.class).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void viewerCanReadAndInactiveBlacklistDoesNotInventFingerprint() {
        String sha = hash();
        jdbc.update("""
                INSERT INTO hash_blacklist(hash_type,hash_value,reason,severity,source,is_active,created_at,deactivated_at)
                VALUES ('SHA256',?,'inactive synthetic','LOW','IMPORT',false,now(),now())
                """, sha);
        String username = "sample-viewer-" + UUID.randomUUID();
        String password = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO admin_users(admin_id,username,password_hash,role,is_active) VALUES (?,?,?,'VIEWER',true)",
                UUID.randomUUID(), username, encoder.encode(password));
        var login = rest.postForEntity("/api/admin/auth/login", Map.of("username",username,"password",password), Map.class);
        var headers = new org.springframework.http.HttpHeaders();
        headers.setBearerAuth((String)login.getBody().get("accessToken"));
        var response = rest.exchange(url(sha.toUpperCase()), org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(headers), Map.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat((Map<String,Object>)response.getBody().get("blacklist")).containsEntry("isActive",false).containsEntry("reason","inactive synthetic");
        assertThat(response.getBody()).containsEntry("fingerprint",null);
    }

    @Test
    void fingerprintOnlyAndUnavailableAreDistinguishedFromMissingSample() {
        String sha = hash();
        jdbc.update("""
                INSERT INTO file_fingerprints(sha256,tlsh,calculation_status,algorithm_version,provenance)
                VALUES (?,NULL,'LOW_ENTROPY','test','VERIFIED_UPLOAD')
                """, sha);
        var response = admin.getForEntity(url(sha), Map.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("blacklist",null);
        assertThat((Map<String,Object>)response.getBody().get("fingerprint"))
                .containsEntry("tlsh",null).containsEntry("calculationStatus","LOW_ENTROPY")
                .containsEntry("provenanceDetail",null);
    }

    @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;

    @Test
    void currentMetadataIsReadFromBothTables() {
        String sha = hash();
        jdbc.update("""
                INSERT INTO hash_blacklist(hash_type,hash_value,reason,severity,source,added_by,is_active,created_at)
                VALUES ('SHA256',?,'synthetic sample review','HIGH','MANUAL',?,true,'2026-10-23T00:00:00Z')
                """, sha, admin.adminId());
        String tlsh = "T1" + "A".repeat(70);
        jdbc.update("""
                INSERT INTO file_fingerprints(sha256,tlsh,calculation_status,algorithm_version,provenance,provenance_detail,imported_by)
                VALUES (?,?,'CALCULATED','test-vector','ADMIN_IMPORT','Synthetic QA metadata, not external feed',?)
                """, sha, tlsh, admin.adminId());
        var response = admin.getForEntity(url(sha), Map.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("sha256")).isEqualTo(sha);
        var blacklist = (Map<String, Object>) response.getBody().get("blacklist");
        assertThat(blacklist).containsEntry("reason", "synthetic sample review").containsEntry("source", "MANUAL")
                .containsEntry("severity", "HIGH").containsEntry("isActive", true)
                .containsEntry("registeredAt", "2026-10-23T00:00:00Z");
        var fingerprint = (Map<String, Object>) response.getBody().get("fingerprint");
        assertThat(fingerprint).containsEntry("tlsh", tlsh).containsEntry("provenance", "ADMIN_IMPORT")
                .containsEntry("provenanceDetail", "Synthetic QA metadata, not external feed")
                .containsEntry("algorithmVersion", "test-vector").containsEntry("calculationStatus", "CALCULATED");
        assertThat(fingerprint.get("calculatedAt")).isNotNull();
    }
}
