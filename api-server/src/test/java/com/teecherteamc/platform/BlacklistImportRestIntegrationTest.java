package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.teecherteamc.platform.lookup.domain.BloomFilterPort;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** POST /api/admin/blacklist/import. 같은 DB를 다른 테스트도 쓰므로 테스트마다 무작위 해시를 쓴다. */
@SuppressWarnings("unchecked")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfiguration.class, AdminApiClient.class})
class BlacklistImportRestIntegrationTest {

    private static final String URL = "/api/admin/blacklist/import";
    // MalwareBazaar recent.csv(2026-11-16)의 실제 TLSH 값. 공식 파서로 읽히는 구조다.
    private static final String TLSH_A = "T158E4396ABD5298A0C4D455BAB75DC2EC330793B9D3EAB207CD05CB24A9CF9894D3D381";
    private static final String TLSH_B = "T11CE45D07F9A0CDB4D5B291B0508E97398921D8391363D98FDE87ED70BD1E190AB2D36E";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AdminApiClient adminApi;

    @Autowired
    private BloomFilterPort bloomFilter;

    private static String randomSha256() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }

    private static Map<String, Object> entry(String sha256, String tlsh, String label) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("sha256", sha256);
        entry.put("tlsh", tlsh);
        entry.put("label", label);
        return entry;
    }

    private ResponseEntity<Map<String, Object>> importBatch(List<Map<String, Object>> entries) {
        return adminApi.postForEntity(URL, Map.of("source", "MalwareBazaar", "entries", entries),
                (Class<Map<String, Object>>) (Class<?>) Map.class);
    }

    private Map<String, Object> blacklistRow(String sha256) {
        return jdbcTemplate.queryForMap(
                "SELECT source, reason, severity, is_active FROM hash_blacklist WHERE hash_value = ?", sha256);
    }

    private List<Map<String, Object>> fingerprint(String sha256) {
        return jdbcTemplate.queryForList(
                "SELECT tlsh, provenance, provenance_detail FROM file_fingerprints WHERE sha256 = ?", sha256);
    }

    private void seedBlacklist(String sha256, boolean active) {
        jdbcTemplate.update("""
                INSERT INTO hash_blacklist (hash_value, reason, source, added_by, is_active)
                VALUES (?, 'seed', 'MANUAL', ?, ?)
                """, sha256, adminApi.adminId(), active);
    }

    @Test
    void 새_해시는_IMPORT로_들어가고_TLSH_지문도_들어간다() {
        String a = randomSha256();
        String b = randomSha256();

        ResponseEntity<Map<String, Object>> response = importBatch(List.of(
                entry(a, TLSH_A, "Mirai"), entry(b, null, "n/a")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("received", 2).containsEntry("added", 2)
                .containsEntry("fingerprintsAdded", 1);
        assertThat(blacklistRow(a)).containsEntry("source", "IMPORT").containsEntry("reason", "MalwareBazaar: Mirai")
                .containsEntry("severity", "HIGH").containsEntry("is_active", true);
        assertThat(blacklistRow(b)).containsEntry("reason", "MalwareBazaar");
        assertThat(fingerprint(a)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("tlsh", TLSH_A).containsEntry("provenance", "ADMIN_IMPORT")
                .containsEntry("provenance_detail", "MalwareBazaar"));
        assertThat(fingerprint(b)).isEmpty();
        assertThat(bloomFilter.mightContain(a)).isTrue();
        assertThat(bloomFilter.mightContain(b)).isTrue();
    }

    @Test
    void 이미_활성인_해시는_건너뛰지만_지문은_보강한다() {
        String a = randomSha256();
        seedBlacklist(a, true);

        ResponseEntity<Map<String, Object>> response = importBatch(List.of(entry(a, TLSH_A, "Mirai")));

        assertThat(response.getBody()).containsEntry("added", 0).containsEntry("skippedActive", 1)
                .containsEntry("fingerprintsAdded", 1);
        assertThat(blacklistRow(a)).containsEntry("source", "MANUAL").containsEntry("reason", "seed");
    }

    @Test
    void 해제된_해시는_되살리지_않는다() {
        String a = randomSha256();
        seedBlacklist(a, false);

        ResponseEntity<Map<String, Object>> response = importBatch(List.of(entry(a, TLSH_A, "Mirai")));

        assertThat(response.getBody()).containsEntry("added", 0).containsEntry("skippedInactive", 1)
                .containsEntry("fingerprintsAdded", 0);
        assertThat(blacklistRow(a)).containsEntry("is_active", false);
        assertThat(fingerprint(a)).isEmpty();
    }

    @Test
    void 유효한_화이트리스트_해시는_건너뛴다() {
        String a = randomSha256();
        jdbcTemplate.update("INSERT INTO hash_whitelist (hash_value, reason, added_by) VALUES (?, 'ok', ?)",
                a, adminApi.adminId());

        ResponseEntity<Map<String, Object>> response = importBatch(List.of(entry(a, TLSH_A, "Mirai")));

        assertThat(response.getBody()).containsEntry("added", 0).containsEntry("skippedWhitelisted", 1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hash_blacklist WHERE hash_value = ?", Integer.class, a)).isZero();
    }

    @Test
    void 잘못된_SHA와_TLSH는_세고_건너뛴다() {
        String a = randomSha256();

        ResponseEntity<Map<String, Object>> response = importBatch(List.of(
                entry("xyz", TLSH_A, "Mirai"), entry(a, "bad", "Mirai")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("invalid", 1).containsEntry("added", 1)
                .containsEntry("fingerprintsAdded", 0).containsEntry("fingerprintsSkipped", 1);
        assertThat(fingerprint(a)).isEmpty();
    }

    @Test
    void 대문자_SHA는_소문자로_바꿔_넣는다() {
        String a = randomSha256();

        importBatch(List.of(entry(a.toUpperCase(), null, "Mirai")));

        assertThat(blacklistRow(a)).containsEntry("is_active", true);
    }

    @Test
    void VERIFIED_UPLOAD_지문은_덮어쓰지_않는다() {
        String a = randomSha256();
        seedBlacklist(a, true);
        jdbcTemplate.update("""
                INSERT INTO file_fingerprints (sha256, tlsh, calculation_status, algorithm_version, provenance)
                VALUES (?, ?, 'CALCULATED', 'test', 'VERIFIED_UPLOAD')
                """, a, TLSH_B);

        ResponseEntity<Map<String, Object>> response = importBatch(List.of(entry(a, TLSH_A, "Mirai")));

        assertThat(response.getBody()).containsEntry("fingerprintsAdded", 0);
        assertThat(fingerprint(a)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("tlsh", TLSH_B).containsEntry("provenance", "VERIFIED_UPLOAD"));
    }

    @Test
    void 천_건을_넘으면_400() {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            entries.add(entry(randomSha256(), null, null));
        }

        ResponseEntity<Map<String, Object>> response = importBatch(entries);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 같은_묶음을_두_번_보내도_결과가_같다() {
        List<Map<String, Object>> entries = List.of(entry(randomSha256(), TLSH_A, "Mirai"),
                entry(randomSha256(), TLSH_B, "Mirai"));
        importBatch(entries);
        long revisionBefore = jdbcTemplate.queryForObject(
                "SELECT revision FROM similarity_corpus_state", Long.class);

        ResponseEntity<Map<String, Object>> second = importBatch(entries);

        assertThat(second.getBody()).containsEntry("added", 0).containsEntry("skippedActive", 2)
                .containsEntry("fingerprintsAdded", 0);
        assertThat(jdbcTemplate.queryForObject("SELECT revision FROM similarity_corpus_state", Long.class))
                .isEqualTo(revisionBefore);
    }

    @Test
    void 감사_로그는_묶음당_한_건() {
        String marker = randomSha256();
        int before = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'IMPORT_BLACKLIST'", Integer.class);

        importBatch(List.of(entry(marker, null, null), entry(randomSha256(), null, null)));

        List<Map<String, Object>> audits = jdbcTemplate.queryForList("""
                SELECT target_type, detail::text AS detail FROM audit_logs
                WHERE action = 'IMPORT_BLACKLIST' ORDER BY created_at DESC LIMIT 1
                """);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'IMPORT_BLACKLIST'", Integer.class))
                .isEqualTo(before + 1);
        assertThat(audits.get(0)).containsEntry("target_type", "BLACKLIST");
        assertThat((String) audits.get(0).get("detail")).contains("MalwareBazaar").contains("\"added\":2");
    }

    @Test
    void 한_건_등록과_동시에_돌려도_교착이_없다() throws Exception {
        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            hashes.add(randomSha256());
        }
        List<Map<String, Object>> entries = hashes.stream().map(h -> entry(h, null, null)).toList();

        CompletableFuture<ResponseEntity<Map<String, Object>>> batch =
                CompletableFuture.supplyAsync(() -> importBatch(entries));
        CompletableFuture<Void> singles = CompletableFuture.runAsync(() -> {
            for (String hash : hashes.reversed().subList(0, 50)) {
                adminApi.postForEntity("/api/admin/blacklist", Map.of("sha256", hash, "reason", "single"),
                        (Class<Map<String, Object>>) (Class<?>) Map.class);
            }
        });

        CompletableFuture.allOf(batch, singles).get(30, TimeUnit.SECONDS);
        assertThat(batch.get().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hash_blacklist WHERE hash_value = ANY(?) AND is_active",
                Integer.class, (Object) hashes.toArray(String[]::new))).isEqualTo(200);
    }
}
