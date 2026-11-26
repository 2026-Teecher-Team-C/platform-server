package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.teecherteamc.platform.appeal.application.AppealLinks;
import com.teecherteamc.platform.proto.verdict.v1.CheckHashRequest;
import com.teecherteamc.platform.proto.verdict.v1.CheckHashResponse;
import com.teecherteamc.platform.proto.verdict.v1.Decision;
import com.teecherteamc.platform.proto.verdict.v1.DecisionSource;
import com.teecherteamc.platform.proto.verdict.v1.VerdictServiceGrpc;
import com.teecherteamc.platform.verdict.domain.VerdictEvidence;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.grpc.client.ImportGrpcClients;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.TestPropertySource;

/**
 * 관리자 소명 승인·거절(POST /api/admin/appeals/{id}/approve|reject). 승인은 화이트리스트 등록과
 * 블랙리스트 해제를 한 트랜잭션으로 처리하므로, 같은 해시의 CheckHash가 실제로 ALLOW가 되는지까지 본다.
 */
@SuppressWarnings("unchecked")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@AutoConfigureTestGrpcTransport
@ImportGrpcClients(types = VerdictServiceGrpc.VerdictServiceBlockingStub.class)
@Import({TestcontainersConfiguration.class, AdminApiClient.class})
@TestPropertySource(properties = {
        "platform.appeal.secret=0123456789abcdef0123456789abcdef",
        "platform.appeal.base-url=http://console.test"
})
class AdminAppealDecisionRestIntegrationTest {

    @Autowired
    private AdminApiClient adminApi;
    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JwtEncoder jwtEncoder;
    @Autowired
    private AppealLinks appealLinks;
    @Autowired
    private VerdictServiceGrpc.VerdictServiceBlockingStub verdictStub;

    @BeforeEach
    void authenticateVerdictClient() {
        verdictStub = VerdictTestClient.authenticated(verdictStub, jdbcTemplate);
    }

    private static Class<Map<String, Object>> mapType() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }

    private record Seeded(UUID appealId, UUID eventId, UUID agentId, String sha256) {
    }

    private UUID seedAgent() {
        UUID deviceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO devices (device_id, hostname, os_platform) VALUES (?, 'PC-DECIDE', 'WINDOWS')", deviceId);
        UUID agentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO agents (agent_id, device_id, agent_version, enrollment_token_hash) VALUES (?, ?, '0.0.1', ?)",
                agentId, deviceId, "hash-" + agentId);
        return agentId;
    }

    private static String newSha() {
        return UUID.randomUUID().toString().replace("-", "").repeat(2);
    }

    private void seedBlockedEvent(UUID eventId, UUID agentId, String sha) {
        jdbcTemplate.update("""
                INSERT INTO download_events(event_id,agent_id,sha256,request_host,url,filename,pipeline_status,decision,decision_source,held_at)
                VALUES (?,?,NULL,'h.example','https://h.example/x','d.exe','COMPLETED','BLOCKED','ENGINE',now())
                """, eventId, agentId);
    }

    /** 차단 기록과 PENDING 소명을 만든다. */
    private Seeded seedPending() {
        UUID agentId = seedAgent();
        String sha = newSha();
        UUID eventId = UUID.randomUUID();
        seedBlockedEvent(eventId, agentId, sha);
        UUID appealId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO appeals(appeal_id,event_id,sha256,agent_id,reason,status,evidence_kind,threat)
                VALUES (?,?,?,?,'업무용 파일입니다','PENDING','BLACKLIST','Trojan.X')
                """, appealId, eventId, sha, agentId);
        return new Seeded(appealId, eventId, agentId, sha);
    }

    private void seedActiveBlacklist(String sha) {
        jdbcTemplate.update(
                "INSERT INTO hash_blacklist (hash_type, hash_value, reason, severity, source, is_active) "
                        + "VALUES ('SHA256', ?, '악성 해시', 'HIGH', 'MANUAL', true)", sha);
    }

    private ResponseEntity<Map<String, Object>> approve(UUID appealId, Map<String, Object> body) {
        return adminApi.postForEntity("/api/admin/appeals/" + appealId + "/approve", body, mapType());
    }

    private ResponseEntity<Map<String, Object>> reject(UUID appealId, Map<String, Object> body) {
        return adminApi.postForEntity("/api/admin/appeals/" + appealId + "/reject", body, mapType());
    }

    private static Map<String, Object> note(String value) {
        Map<String, Object> body = new HashMap<>();
        body.put("note", value);
        return body;
    }

    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    @Test
    void approve_whitelists_and_checkhash_allows() {
        Seeded s = seedPending();
        seedActiveBlacklist(s.sha256());   // 블룸·블랙리스트에 있어도 화이트리스트가 먼저 조회된다

        ResponseEntity<Map<String, Object>> response = approve(s.appealId(), note("업무 프로그램 확인"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("appealId", s.appealId().toString())
                .containsEntry("status", "APPROVED").containsEntry("decisionNote", "업무 프로그램 확인")
                .containsEntry("decidedBy", adminApi.username()).containsEntry("blacklisted", false);
        assertThat(response.getBody().get("decidedAt")).isNotNull();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT is_active, origin_appeal_id, origin_quarantine_id, reason, added_by, expires_at "
                        + "FROM hash_whitelist WHERE hash_value = ?", s.sha256());
        assertThat(row.get("is_active")).isEqualTo(true);
        assertThat(row.get("origin_appeal_id")).isEqualTo(s.appealId());
        assertThat(row.get("origin_quarantine_id")).isNull();
        assertThat(row.get("reason")).isEqualTo("업무 프로그램 확인");
        assertThat(row.get("added_by")).isEqualTo(adminApi.adminId());
        assertThat(row.get("expires_at")).isNull();

        CheckHashResponse check = verdictStub.checkHash(CheckHashRequest.newBuilder().setSha256(s.sha256()).build());
        assertThat(check.getDecision()).isEqualTo(Decision.DECISION_ALLOW);
        assertThat(check.getSource()).isEqualTo(DecisionSource.DECISION_SOURCE_WHITELIST);
    }

    @Test
    void approve_without_note_uses_default_reason() {
        Seeded s = seedPending();

        ResponseEntity<Map<String, Object>> response = approve(s.appealId(), Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("decisionNote")).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT reason FROM hash_whitelist WHERE hash_value = ?",
                String.class, s.sha256())).isEqualTo("소명 승인");
    }

    @Test
    void approve_note_over_1000_is_400() {
        Seeded s = seedPending();

        assertThat(approve(s.appealId(), note("a".repeat(1001))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM appeals WHERE appeal_id = ?", String.class,
                s.appealId())).isEqualTo("PENDING");
    }

    @Test
    void approve_releases_active_blacklist() {
        Seeded s = seedPending();
        seedActiveBlacklist(s.sha256());

        assertThat(approve(s.appealId(), note("확인")).getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> bl = jdbcTemplate.queryForMap(
                "SELECT is_active, deactivated_at FROM hash_blacklist WHERE hash_value = ?", s.sha256());
        assertThat(bl.get("is_active")).isEqualTo(false);
        assertThat(bl.get("deactivated_at")).isNotNull();
        Map<String, Object> audit = jdbcTemplate.queryForMap(
                "SELECT actor_id, detail::jsonb->>'reason' AS reason, detail::jsonb->>'appealId' AS appeal_id FROM audit_logs "
                        + "WHERE action = 'REMOVE_BLACKLIST' AND target_id = ?", "SHA256:" + s.sha256());
        assertThat(audit.get("actor_id")).isEqualTo(adminApi.adminId());
        assertThat(audit.get("reason")).isEqualTo("소명 승인으로 자동 해제");
        assertThat(audit.get("appeal_id")).isEqualTo(s.appealId().toString());
    }

    @Test
    void approve_reactivates_released_whitelist_with_appeal_origin() {
        Seeded s = seedPending();
        jdbcTemplate.update(
                "INSERT INTO hash_whitelist (hash_value, reason, added_by, is_active) VALUES (?, '예전 등록', ?, false)",
                s.sha256(), adminApi.adminId());

        assertThat(approve(s.appealId(), note("다시 허용")).getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT is_active, origin_appeal_id, reason FROM hash_whitelist WHERE hash_value = ?", s.sha256());
        assertThat(row.get("is_active")).isEqualTo(true);
        assertThat(row.get("origin_appeal_id")).isEqualTo(s.appealId());
        assertThat(row.get("reason")).isEqualTo("다시 허용");
    }

    @Test
    void approve_with_existing_whitelist_keeps_row() {
        Seeded s = seedPending();
        jdbcTemplate.update(
                "INSERT INTO hash_whitelist (hash_value, reason, added_by, is_active) VALUES (?, '기존 등록', ?, true)",
                s.sha256(), adminApi.adminId());

        ResponseEntity<Map<String, Object>> response = approve(s.appealId(), note("확인"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "APPROVED");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT reason, origin_appeal_id FROM hash_whitelist WHERE hash_value = ?", s.sha256());
        assertThat(row.get("reason")).isEqualTo("기존 등록");
        assertThat(row.get("origin_appeal_id")).isNull();
        assertThat(count("SELECT count(*) FROM audit_logs WHERE action = 'APPROVE_APPEAL' AND target_id = ?",
                s.appealId().toString())).isEqualTo(1);
    }

    @Test
    void approve_records_dangerous_audit() {
        Seeded s = seedPending();

        approve(s.appealId(), note("확인"));

        Map<String, Object> audit = jdbcTemplate.queryForMap(
                "SELECT actor_id, target_type, detail::jsonb->>'sha256' AS sha, detail::jsonb->>'note' AS note FROM audit_logs "
                        + "WHERE action = 'APPROVE_APPEAL' AND target_id = ?", s.appealId().toString());
        assertThat(audit.get("actor_id")).isEqualTo(adminApi.adminId());
        assertThat(audit.get("target_type")).isEqualTo("APPEAL");
        assertThat(audit.get("sha")).isEqualTo(s.sha256());
        assertThat(audit.get("note")).isEqualTo("확인");

        List<Map<String, Object>> items = (List<Map<String, Object>>) adminApi
                .getForEntity("/api/admin/audit-logs?dangerousOnly=true&action=APPROVE_APPEAL&size=200", Map.class)
                .getBody().get("content");
        assertThat(items).anyMatch(i -> s.appealId().toString().equals(i.get("targetId")));
    }

    @Test
    void reject_requires_note() {
        Seeded s = seedPending();

        assertThat(reject(s.appealId(), Map.of()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reject(s.appealId(), note("   ")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reject(s.appealId(), note("a".repeat(1001))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reject(s.appealId(), note("ab\u0000c")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM appeals WHERE appeal_id = ?", String.class,
                s.appealId())).isEqualTo("PENDING");
    }

    @Test
    void reject_records_note_and_audit() {
        Seeded s = seedPending();

        ResponseEntity<Map<String, Object>> response = reject(s.appealId(), note("  악성 맞음  "));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "REJECTED").containsEntry("decisionNote", "악성 맞음")
                .containsEntry("decidedBy", adminApi.username());
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, decision_note, decided_by, decided_at FROM appeals WHERE appeal_id = ?", s.appealId());
        assertThat(row.get("status")).isEqualTo("REJECTED");
        assertThat(row.get("decision_note")).isEqualTo("악성 맞음");
        assertThat(row.get("decided_by")).isEqualTo(adminApi.adminId());
        assertThat(row.get("decided_at")).isNotNull();
        Map<String, Object> audit = jdbcTemplate.queryForMap(
                "SELECT actor_id, target_type, detail::jsonb->>'sha256' AS sha, detail::jsonb->>'note' AS note FROM audit_logs "
                        + "WHERE action = 'REJECT_APPEAL' AND target_id = ?", s.appealId().toString());
        assertThat(audit.get("actor_id")).isEqualTo(adminApi.adminId());
        assertThat(audit.get("target_type")).isEqualTo("APPEAL");
        assertThat(audit.get("sha")).isEqualTo(s.sha256());
        assertThat(audit.get("note")).isEqualTo("악성 맞음");
    }

    @Test
    void reject_does_not_touch_whitelist() {
        Seeded s = seedPending();
        seedActiveBlacklist(s.sha256());

        assertThat(reject(s.appealId(), note("거절")).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(count("SELECT count(*) FROM hash_whitelist WHERE hash_value = ?", s.sha256())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT is_active FROM hash_blacklist WHERE hash_value = ?",
                Boolean.class, s.sha256())).isTrue();
        CheckHashResponse check = verdictStub.checkHash(CheckHashRequest.newBuilder().setSha256(s.sha256()).build());
        // SQL로 넣은 블랙리스트는 블룸에 없어 UNKNOWN일 수 있다. 핵심은 화이트리스트로 통과되지 않는 것이다.
        assertThat(check.getDecision()).isNotEqualTo(Decision.DECISION_ALLOW);
        assertThat(check.getSource()).isNotEqualTo(DecisionSource.DECISION_SOURCE_WHITELIST);
    }

    @Test
    void decided_appeal_cannot_be_decided_again() {
        Seeded s = seedPending();
        assertThat(approve(s.appealId(), note("확인")).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map<String, Object>> again = approve(s.appealId(), note("또"));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody()).containsEntry("code", "CONFLICT");
        assertThat(again.getBody().get("message")).isEqualTo("다른 담당자가 먼저 처리했습니다");
        assertThat(reject(s.appealId(), note("거절")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM appeals WHERE appeal_id = ?", String.class,
                s.appealId())).isEqualTo("APPROVED");
    }

    @Test
    void unknown_appeal_is_404() {
        assertThat(approve(UUID.randomUUID(), Map.of()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(reject(UUID.randomUUID(), note("x")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void concurrent_approve_one_wins() throws Exception {
        Seeded s = seedPending();
        adminApi.accessToken();   // 로그인을 먼저 끝내 두 스레드가 같은 토큰을 쓰게 한다
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<HttpStatus>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return (HttpStatus) approve(s.appealId(), note("동시")).getStatusCode();
                }));
            }
            start.countDown();
            List<HttpStatus> statuses = new ArrayList<>();
            for (Future<HttpStatus> f : futures) {
                statuses.add(f.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM hash_whitelist WHERE hash_value = ?", s.sha256())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_logs WHERE action = 'APPROVE_APPEAL' AND target_id = ?",
                s.appealId().toString())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_logs WHERE action = 'ADD_WHITELIST' AND target_id = ?",
                "SHA256:" + s.sha256())).isEqualTo(1);
    }

    @Test
    void analyst_cannot_decide() {
        Seeded s = seedPending();
        String token = analystToken();

        assertThat(post("/api/admin/appeals/" + s.appealId() + "/approve", token, Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post("/api/admin/appeals/" + s.appealId() + "/reject", token, note("거절")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM appeals WHERE appeal_id = ?", String.class,
                s.appealId())).isEqualTo("PENDING");
        assertThat(count("SELECT count(*) FROM hash_whitelist WHERE hash_value = ?", s.sha256())).isZero();
    }

    @Test
    void anonymous_cannot_decide() {
        Seeded s = seedPending();
        assertThat(restTemplate.postForEntity("/api/admin/appeals/" + s.appealId() + "/approve", Map.of(), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void public_get_shows_decision() {
        UUID agentId = seedAgent();
        String sha = newSha();
        UUID eventId = UUID.randomUUID();
        seedBlockedEvent(eventId, agentId, sha);
        String token = appealLinks.tokens().issue(eventId, sha, agentId, VerdictEvidence.similarity(12, newSha(), "X"));
        assertThat(restTemplate.postForEntity("/api/public/appeals/" + token, Map.of("reason", "업무용"), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID appealId = jdbcTemplate.queryForObject("SELECT appeal_id FROM appeals WHERE event_id = ?", UUID.class,
                eventId);

        assertThat(reject(appealId, note("업무 무관 파일")).getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> appeal = (Map<String, Object>) restTemplate
                .getForEntity("/api/public/appeals/" + token, Map.class).getBody().get("appeal");
        assertThat(appeal).containsEntry("status", "REJECTED").containsEntry("decisionNote", "업무 무관 파일");
        assertThat(appeal.get("decidedAt")).isNotNull();
    }

    private ResponseEntity<Map> post(String url, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private String analystToken() {
        UUID adminId = UUID.randomUUID();
        String username = "decide-analyst-" + adminId;
        jdbcTemplate.update("""
                INSERT INTO admin_users (admin_id, username, password_hash, role, is_active)
                VALUES (?, ?, 'unused-hash', 'ANALYST', true)
                """, adminId, username);
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().subject(adminId.toString()).claim("username", username)
                .issuedAt(now).expiresAt(now.plusSeconds(600)).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
