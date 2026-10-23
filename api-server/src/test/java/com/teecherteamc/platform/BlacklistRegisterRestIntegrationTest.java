package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

// 인증 없음 — 데모 범위로 명시적으로 생략(2026-10-14 요구사항). added_by는 null로 들어간다.
@SuppressWarnings("unchecked")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
class BlacklistRegisterRestIntegrationTest {

    private static final String URL = "/api/admin/blacklist";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 같은 DB를 다른 테스트도 쓰므로 테스트마다 다른 해시를 쓴다. UUID 둘을 이으면 소문자 hex 64자.
    private static String randomSha256() {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
    }

    private ResponseEntity<Map<String, Object>> post(String sha256, String reason) {
        return restTemplate.postForEntity(
                URL,
                Map.of("sha256", sha256, "reason", reason),
                (Class<Map<String, Object>>) (Class<?>) Map.class);
    }

    private int countBlacklist(String hash) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hash_blacklist WHERE hash_value = ?", Integer.class, hash);
    }

    private int countAudit(String hash) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'ADD_BLACKLIST' AND target_id = ?",
                Integer.class, hash);
    }

    // hash_whitelist.added_by가 NOT NULL + admin_users FK라서 관리자 행이 먼저 필요하다.
    private void seedWhitelist(String hash, Instant expiresAt) {
        UUID adminId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO admin_users (admin_id, username, password_hash) VALUES (?, ?, 'test')",
                adminId, "test-" + adminId);
        jdbcTemplate.update(
                "INSERT INTO hash_whitelist (hash_value, reason, added_by, expires_at) VALUES (?, 'test', ?, ?)",
                hash, adminId, expiresAt == null ? null : Timestamp.from(expiresAt));
    }

    @Test
    void 정상_등록하면_201과_BlacklistItem을_돌려주고_DB에_저장된다() {
        String hash = randomSha256();

        ResponseEntity<Map<String, Object>> response = post(hash, "알려진 악성 해시");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("hashValue")).isEqualTo(hash);
        assertThat(body.get("reason")).isEqualTo("알려진 악성 해시");
        assertThat(body.get("severity")).isEqualTo("HIGH");
        assertThat(body.get("source")).isEqualTo("MANUAL");
        assertThat(body.get("isActive")).isEqualTo(true);
        assertThat(body.get("addedBy")).isNull();   // 로그인이 없어서 null
        assertThat(body.get("deactivatedAt")).isNull();
        // createdAt은 ISO-8601 문자열이어야 한다 (파싱되면 통과)
        assertThat(Instant.parse((String) body.get("createdAt"))).isNotNull();
        assertThat(countBlacklist(hash)).isEqualTo(1);
    }

    @Test
    void 대문자와_공백이_섞인_해시는_소문자로_정리되어_저장된다() {
        String hash = randomSha256();

        ResponseEntity<Map<String, Object>> response = post("  " + hash.toUpperCase() + "  ", "사유");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("hashValue")).isEqualTo(hash);
        assertThat(countBlacklist(hash)).isEqualTo(1);
    }

    @Test
    void 이미_활성인_해시를_또_등록하면_409이다() {
        String hash = randomSha256();
        post(hash, "처음");

        ResponseEntity<Map<String, Object>> response = post(hash, "두 번째");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("CONFLICT");
        assertThat(response.getBody().get("message")).isEqualTo("이미 블랙리스트에 등록된 해시입니다.");
        assertThat(countBlacklist(hash)).isEqualTo(1);
        assertThat(countAudit(hash)).isEqualTo(1);   // 실패한 요청은 로그를 남기지 않는다
    }

    @Test
    void 유효한_화이트리스트에_있으면_409이고_블랙리스트에_들어가지_않는다() {
        String hash = randomSha256();
        seedWhitelist(hash, null);   // 만료 없음

        ResponseEntity<Map<String, Object>> response = post(hash, "사유");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("CONFLICT");
        assertThat(response.getBody().get("message")).isEqualTo("화이트리스트에 등록된 해시입니다.");
        assertThat(countBlacklist(hash)).isZero();
        assertThat(countAudit(hash)).isZero();
    }

    @Test
    void 만료된_화이트리스트는_충돌로_보지_않아_등록된다() {
        // 유스케이스가 isEffectiveAt(now)로 판단하는 것을 고정하는 테스트.
        // 스펙대로 "활성이기만 하면 충돌"로 바꾸면 이 테스트를 지우거나 고쳐야 한다.
        String hash = randomSha256();
        seedWhitelist(hash, Instant.parse("2020-01-01T00:00:00Z"));

        ResponseEntity<Map<String, Object>> response = post(hash, "사유");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void 해제된_해시를_다시_등록하면_새_행_없이_되살아난다() {
        String hash = randomSha256();
        post(hash, "처음 사유");
        // 해제 API가 아직 없어서 DB에서 직접 해제 상태로 만든다
        jdbcTemplate.update(
                "UPDATE hash_blacklist SET is_active = false, deactivated_at = now() WHERE hash_value = ?",
                hash);

        ResponseEntity<Map<String, Object>> response = post(hash, "다시 등록한 사유");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("isActive")).isEqualTo(true);
        assertThat(body.get("deactivatedAt")).isNull();
        assertThat(body.get("reason")).isEqualTo("다시 등록한 사유");
        assertThat(countBlacklist(hash)).isEqualTo(1);   // INSERT가 아니라 기존 행을 되살렸다
        assertThat(countAudit(hash)).isEqualTo(2);       // 등록 1번 + 재등록 1번
    }

    @Test
    void sha256_형식이_틀리면_400이다() {
        ResponseEntity<Map<String, Object>> response = post("not-a-hash", "사유");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_INPUT");
        assertThat(response.getBody().get("path")).isEqualTo(URL);
    }

    @Test
    void reason이_비어_있으면_400이다() {
        String hash = randomSha256();

        ResponseEntity<Map<String, Object>> response = post(hash, "   ");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_INPUT");
        assertThat(countBlacklist(hash)).isZero();
    }

    @Test
    void 등록하면_ADD_BLACKLIST_감사_로그가_남는다() {
        String hash = randomSha256();

        post(hash, "사유");

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT action, target_type, target_id, actor_id FROM audit_logs WHERE target_id = ?", hash);
        assertThat(row.get("action")).isEqualTo("ADD_BLACKLIST");
        assertThat(row.get("target_type")).isEqualTo("BLACKLIST");
        assertThat(row.get("target_id")).isEqualTo(hash);
        assertThat(row.get("actor_id")).isNull();   // 로그인이 없어서 null
    }
}