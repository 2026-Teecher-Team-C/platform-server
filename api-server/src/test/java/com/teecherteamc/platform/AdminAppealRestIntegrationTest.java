package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/** 관리자 소명 조회 API(목록·상세). 목록은 ADMIN·ANALYST, 비로그인은 401. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfiguration.class, AdminApiClient.class})
class AdminAppealRestIntegrationTest {

    @Autowired
    private AdminApiClient adminClient;
    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JwtEncoder jwtEncoder;

    private UUID seedAgent(String hostname) {
        UUID deviceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO devices (device_id, hostname, os_platform) VALUES (?, ?, 'WINDOWS')", deviceId, hostname);
        UUID agentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO agents (agent_id, device_id, agent_version, enrollment_token_hash) VALUES (?, ?, '0.0.1', ?)",
                agentId, deviceId, "hash-" + agentId);
        return agentId;
    }

    private String newSha() {
        return UUID.randomUUID().toString().replace("-", "").repeat(2);
    }

    private UUID seedEvent(UUID agentId, String sha, String filename) {
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO file_verdicts(sha256,verdict,verdict_source) VALUES (?,'MALICIOUS','MANUAL') ON CONFLICT DO NOTHING", sha);
        jdbcTemplate.update("""
                INSERT INTO download_events(event_id,agent_id,sha256,request_host,url,filename,pipeline_status,decision,decision_source,held_at)
                VALUES (?,?,?,'h.example','https://h.example/x',?,'COMPLETED','BLOCKED','ENGINE',now())
                """, eventId, agentId, sha, filename);
        return eventId;
    }

    private UUID seedAppeal(UUID eventId, UUID agentId, String sha, String reason, String status, Instant createdAt) {
        UUID appealId = UUID.randomUUID();
        UUID decidedBy = null;
        Timestamp decidedAt = null;
        String note = null;
        if (!status.equals("PENDING")) {
            decidedBy = adminClient.adminId();
            decidedAt = Timestamp.from(createdAt.plusSeconds(60));
            note = "의견";
        }
        jdbcTemplate.update("""
                INSERT INTO appeals(appeal_id,event_id,sha256,agent_id,reason,status,evidence_kind,threat,decided_by,decision_note,created_at,decided_at)
                VALUES (?,?,?,?,?,?,'BLACKLIST','Trojan.X',?,?,?,?)
                """, appealId, eventId, sha, agentId, reason, status, decidedBy, note, Timestamp.from(createdAt), decidedAt);
        return appealId;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getMap(String url) {
        return (Map<String, Object>) (Map<?, ?>) adminClient.getForEntity(url, Map.class).getBody();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> content(String url) {
        return (List<Map<String, Object>>) getMap(url).get("content");
    }

    @Test
    void list_defaults_to_all_newest_first() {
        UUID agent = seedAgent("PC-LIST-ALL");
        String sha = newSha();
        UUID older = seedAppeal(seedEvent(agent, sha, "a.exe"), agent, sha, "예전", "APPROVED", Instant.parse("2026-11-01T00:00:00Z"));
        UUID newer = seedAppeal(seedEvent(agent, sha, "b.exe"), agent, sha, "최근", "PENDING", Instant.parse("2026-11-02T00:00:00Z"));

        List<Map<String, Object>> rows = content("/api/admin/appeals?size=200");
        List<Object> ids = rows.stream().map(r -> r.get("appealId")).toList();
        assertThat(ids).contains(older.toString(), newer.toString());
        assertThat(ids.indexOf(newer.toString())).isLessThan(ids.indexOf(older.toString()));
        Map<String, Object> item = rows.stream().filter(r -> newer.toString().equals(r.get("appealId"))).findFirst().orElseThrow();
        assertThat(item).containsEntry("hostname", "PC-LIST-ALL").containsEntry("filename", "b.exe")
                .containsEntry("status", "PENDING").containsEntry("evidenceKind", "BLACKLIST")
                .containsEntry("threat", "Trojan.X").containsEntry("reasonPreview", "최근");
        assertThat(item.get("decidedAt")).isNull();
    }

    @Test
    void list_filters_pending() {
        UUID agent = seedAgent("PC-LIST-PENDING");
        String sha = newSha();
        UUID done = seedAppeal(seedEvent(agent, sha, "a.exe"), agent, sha, "끝", "REJECTED", Instant.parse("2026-11-01T00:00:00Z"));
        UUID pending = seedAppeal(seedEvent(agent, sha, "b.exe"), agent, sha, "대기", "PENDING", Instant.parse("2026-11-02T00:00:00Z"));

        List<Map<String, Object>> rows = content("/api/admin/appeals?status=PENDING&size=200");
        assertThat(rows).allMatch(r -> "PENDING".equals(r.get("status")));
        List<Object> ids = rows.stream().map(r -> r.get("appealId")).toList();
        assertThat(ids).contains(pending.toString()).doesNotContain(done.toString());
    }

    @Test
    void list_invalid_status_is_400() {
        assertThat(adminClient.getForEntity("/api/admin/appeals?status=BOGUS", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void reason_preview_is_80_code_points() {
        UUID agent = seedAgent("PC-PREVIEW");
        String sha = newSha();
        String reason = "😀".repeat(100);
        UUID id = seedAppeal(seedEvent(agent, sha, "p.exe"), agent, sha, reason, "PENDING", Instant.parse("2026-11-03T00:00:00Z"));

        Map<String, Object> item = content("/api/admin/appeals?size=200").stream()
                .filter(r -> id.toString().equals(r.get("appealId"))).findFirst().orElseThrow();
        String preview = (String) item.get("reasonPreview");
        assertThat(preview.codePointCount(0, preview.length())).isEqualTo(80);
        assertThat(preview).isEqualTo("😀".repeat(80));
    }

    @Test
    void detail_has_reason_and_snapshot() {
        UUID agent = seedAgent("PC-DETAIL");
        String sha = newSha();
        UUID event = seedEvent(agent, sha, "d.exe");
        UUID id = seedAppeal(event, agent, sha, "전체 사유", "APPROVED", Instant.parse("2026-11-04T00:00:00Z"));

        Map<String, Object> body = getMap("/api/admin/appeals/" + id);
        assertThat(body).containsEntry("appealId", id.toString()).containsEntry("reason", "전체 사유")
                .containsEntry("eventId", event.toString()).containsEntry("agentId", agent.toString())
                .containsEntry("hostname", "PC-DETAIL").containsEntry("filename", "d.exe")
                .containsEntry("decisionNote", "의견").containsEntry("decidedBy", adminClient.username())
                .containsEntry("blacklisted", false);
        assertThat(body.get("blockedAt")).isNotNull();
        assertThat(body).containsKey("similarity");
        assertThat(body.get("similarity")).isNull();
    }

    @Test
    void detail_includes_similarity_of_the_event() {
        UUID agent = seedAgent("PC-SIM");
        String sha = newSha();
        String sample = newSha();
        UUID assessment = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO file_verdicts(sha256,verdict,verdict_source) VALUES (?,'CLEAN','ENGINE')", sha);
        jdbcTemplate.update("""
                INSERT INTO similarity_assessments(assessment_id,sha256,corpus_token,policy_version,
                    matched_sample_sha256,matched_sample_tlsh,distance,classification,assessed_at)
                VALUES (?,?,'private-token','v1',?,?,17,'BLOCK',now())
                """, assessment, sha, sample, "T1" + "A".repeat(70));
        jdbcTemplate.update("INSERT INTO event_decision_evidence(event_id,agent_id,sha256,assessment_id) VALUES (?,?,?,?)",
                event, agent, sha, assessment);
        jdbcTemplate.update("""
                INSERT INTO download_events(event_id,agent_id,sha256,request_host,url,filename,pipeline_status,decision,decision_source,held_at)
                VALUES (?,?,?,'h.example','https://h.example/x','s.exe','COMPLETED','BLOCKED','SIMILARITY',now())
                """, event, agent, sha);
        UUID id = seedAppeal(event, agent, sha, "유사도", "PENDING", Instant.parse("2026-11-05T00:00:00Z"));

        @SuppressWarnings("unchecked")
        Map<String, Object> sim = (Map<String, Object>) getMap("/api/admin/appeals/" + id).get("similarity");
        assertThat(sim).containsEntry("classification", "BLOCK").containsEntry("distance", 17)
                .containsEntry("matchedSampleSha256", sample);
    }

    @Test
    void detail_blacklisted_true_when_active_blacklist() {
        UUID agent = seedAgent("PC-BL");
        String sha = newSha();
        UUID id = seedAppeal(seedEvent(agent, sha, "bl.exe"), agent, sha, "블랙", "PENDING", Instant.parse("2026-11-06T00:00:00Z"));
        jdbcTemplate.update("INSERT INTO hash_blacklist(hash_type,hash_value,reason,is_active) VALUES ('SHA256',?,'t',true)", sha);
        assertThat(getMap("/api/admin/appeals/" + id)).containsEntry("blacklisted", true);

        jdbcTemplate.update("UPDATE hash_blacklist SET is_active=false, deactivated_at=now() WHERE hash_value=?", sha);
        assertThat(getMap("/api/admin/appeals/" + id)).containsEntry("blacklisted", false);
    }

    @Test
    void detail_without_event_row_has_null_filename() {
        UUID agent = seedAgent("PC-NOEVENT");
        UUID id = seedAppeal(UUID.randomUUID(), agent, newSha(), "이벤트 없음", "PENDING", Instant.parse("2026-11-07T00:00:00Z"));
        Map<String, Object> body = getMap("/api/admin/appeals/" + id);
        assertThat(body.get("filename")).isNull();
        assertThat(body.get("blockedAt")).isNull();
        assertThat(body.get("similarity")).isNull();
    }

    @Test
    void detail_unknown_is_404() {
        assertThat(adminClient.getForEntity("/api/admin/appeals/" + UUID.randomUUID(), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void analyst_can_list_and_view() {
        UUID agent = seedAgent("PC-ANALYST");
        String sha = newSha();
        UUID id = seedAppeal(seedEvent(agent, sha, "an.exe"), agent, sha, "분석가", "PENDING", Instant.parse("2026-11-08T00:00:00Z"));
        String token = analystToken();
        assertThat(get("/api/admin/appeals", token).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/admin/appeals/" + id, token).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anonymous_is_401() {
        assertThat(restTemplate.getForEntity("/api/admin/appeals", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.getForEntity("/api/admin/appeals/" + UUID.randomUUID(), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private ResponseEntity<Map> get(String url, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    private String analystToken() {
        UUID adminId = UUID.randomUUID();
        String username = "appeal-analyst-" + adminId;
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
