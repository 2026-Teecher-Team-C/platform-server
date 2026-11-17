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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * POST /api/admin/blacklist. 로그인이 필요한 경로라서 AdminApiClient로 로그인해서 요청한다.
 * 같은 DB를 다른 테스트도 쓰므로 테스트마다 다른 해시를 쓴다.
 */
@SuppressWarnings("unchecked")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfiguration.class, AdminApiClient.class})
class BlacklistRegisterRestIntegrationTest {

    private static final String URL = "/api/admin/blacklist";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AdminApiClient adminApi;

    // UUID 둘을 이으면 소문자 hex 64자다.
    private static String randomSha256() {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
    }

    private static Class<Map<String, Object>> mapType() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }

    private ResponseEntity<Map<String, Object>> post(String sha256, String reason) {
        return adminApi.postForEntity(URL, Map.of("sha256", sha256, "reason", reason), mapType());
    }

    // 응답이 {entry, affectedDevices}로 감싸져 있다(IMP-10). entry는 기존 BlacklistItem과 같은 모양이다.
    @SuppressWarnings("unchecked")
    private static Map<String, Object> entry(ResponseEntity<Map<String, Object>> response) {
        return (Map<String, Object>) response.getBody().get("entry");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> affectedDevices(ResponseEntity<Map<String, Object>> response) {
        return (List<Map<String, Object>>) response.getBody().get("affectedDevices");
    }

    private int countBlacklist(String hash) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hash_blacklist WHERE hash_value = ?", Integer.class, hash);
    }

    // 감사 로그 규격: 해시 리스트의 target_id는 "SHA256:해시값"
    private int countAudit(String hash) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'ADD_BLACKLIST' AND target_id = ?",
                Integer.class, "SHA256:" + hash);
    }

    // hash_whitelist.added_by는 NOT NULL이라 로그인한 테스트 관리자를 쓴다.
    private void seedWhitelist(String hash, Instant expiresAt) {
        jdbcTemplate.update(
                "INSERT INTO hash_whitelist (hash_value, reason, added_by, expires_at) VALUES (?, 'test', ?, ?)",
                hash, adminApi.adminId(), expiresAt == null ? null : Timestamp.from(expiresAt));
    }

    // IMP-10: 영향받은 기기 조회 테스트용 — device/agent/download_event 픽스처.
    private UUID seedDevice(String hostname) {
        UUID deviceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO devices (device_id, hostname, os_platform) VALUES (?, ?, 'WINDOWS')",
                deviceId, hostname);
        return deviceId;
    }

    private UUID seedAgent(UUID deviceId) {
        UUID agentId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO agents (agent_id, device_id, agent_version, enrollment_token_hash)
                VALUES (?, ?, '0.0.1', ?)
                """,
                agentId, deviceId, "hash-" + agentId);
        return agentId;
    }

    private void seedDownloadEvent(UUID agentId, String sha256, String decision, Instant createdAt) {
        jdbcTemplate.update(
                """
                INSERT INTO download_events
                    (event_id, agent_id, sha256, request_host, url, pipeline_status,
                     decision, decision_source, held_at, decided_at, created_at)
                VALUES (?, ?, ?, 'example.com', 'https://example.com/f', 'COMPLETED', ?, 'CACHE', ?, ?, ?)
                """,
                UUID.randomUUID(), agentId, sha256, decision,
                Timestamp.from(createdAt), Timestamp.from(createdAt), Timestamp.from(createdAt));
    }

    // 받은 걸로 칠 기본 fixture: 호스트 하나가 특정 시각에 그 해시를 RELEASED로 받았다.
    private UUID seedReceivedDevice(String sha256, String hostname, Instant receivedAt) {
        UUID deviceId = seedDevice(hostname);
        UUID agentId = seedAgent(deviceId);
        seedDownloadEvent(agentId, sha256, "RELEASED", receivedAt);
        return deviceId;
    }

    @Test
    void 정상_등록하면_201과_BlacklistItem을_돌려주고_로그인한_관리자가_등록자다() {
        String hash = randomSha256();

        ResponseEntity<Map<String, Object>> response = post(hash, "알려진 악성 해시");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = entry(response);
        assertThat(body.get("hashType")).isEqualTo("SHA256");
        assertThat(body.get("hashValue")).isEqualTo(hash);
        assertThat(body.get("reason")).isEqualTo("알려진 악성 해시");
        assertThat(body.get("severity")).isEqualTo("HIGH");
        assertThat(body.get("source")).isEqualTo("MANUAL");
        assertThat(body.get("isActive")).isEqualTo(true);
        assertThat(body.get("deactivatedAt")).isNull();
        // createdAt은 ISO-8601 문자열이어야 한다 (파싱되면 통과)
        assertThat(Instant.parse((String) body.get("createdAt"))).isNotNull();
        // 등록자는 로그인한 관리자
        Map<String, Object> addedBy = (Map<String, Object>) body.get("addedBy");
        assertThat(addedBy.get("adminId")).isEqualTo(adminApi.adminId().toString());
        assertThat(addedBy.get("username")).isEqualTo(adminApi.username());
        // DB에도 added_by로 들어갔다
        UUID savedAddedBy = jdbcTemplate.queryForObject(
                "SELECT added_by FROM hash_blacklist WHERE hash_value = ?", UUID.class, hash);
        assertThat(savedAddedBy).isEqualTo(adminApi.adminId());
        // 받은 기기가 없으면 빈 배열(IMP-10)
        assertThat(affectedDevices(response)).isEmpty();
    }

    @Test
    void 대문자와_공백이_섞인_해시는_소문자로_정리되어_저장된다() {
        String hash = randomSha256();

        ResponseEntity<Map<String, Object>> response = post("  " + hash.toUpperCase() + "  ", "사유");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(entry(response).get("hashValue")).isEqualTo(hash);
        assertThat(countBlacklist(hash)).isEqualTo(1);
    }

    @Test
    void 이미_활성인_해시를_또_등록하면_409이고_감사_로그는_한_번만_남는다() {
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
    void 유효한_화이트리스트에_있으면_409이고_블랙리스트와_감사_로그에_남지_않는다() {
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
        // 카드대로 "활성이기만 하면 충돌"로 바꾸면 이 테스트를 지우거나 409 기대로 고쳐야 한다.
        String hash = randomSha256();
        seedWhitelist(hash, Instant.parse("2020-01-01T00:00:00Z"));

        ResponseEntity<Map<String, Object>> response = post(hash, "사유");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void 해제된_해시를_다시_등록하면_새_행_없이_되살아나고_감사_로그가_또_남는다() {
        String hash = randomSha256();
        post(hash, "처음 사유");
        // 해제 API가 아직 없어서 DB에서 직접 해제 상태로 만든다
        jdbcTemplate.update(
                "UPDATE hash_blacklist SET is_active = false, deactivated_at = now() WHERE hash_value = ?",
                hash);

        ResponseEntity<Map<String, Object>> response = post(hash, "다시 등록한 사유");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = entry(response);
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
    void reason이_비어_있으면_400과_필드별_사유를_준다() {
        String hash = randomSha256();

        ResponseEntity<Map<String, Object>> response = post(hash, "   ");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_INPUT");
        List<Map<String, Object>> fieldErrors = (List<Map<String, Object>>) response.getBody().get("fieldErrors");
        assertThat(fieldErrors).extracting(e -> e.get("field")).contains("reason");
        assertThat(countBlacklist(hash)).isZero();
    }

    @Test
    void 로그인하지_않으면_401이다() {
        ResponseEntity<Map<String, Object>> response = restTemplate.postForEntity(
                URL, Map.of("sha256", randomSha256(), "reason", "사유"), mapType());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code")).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void 등록하면_ADD_BLACKLIST_감사_로그가_로그인한_관리자와_IP로_남는다() {
        String hash = randomSha256();

        post(hash, "사유");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT action, target_type, target_id, actor_id, host(ip_address) AS ip
                FROM audit_logs WHERE target_id = ?
                """,
                "SHA256:" + hash);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("action")).isEqualTo("ADD_BLACKLIST");
        assertThat(row.get("target_type")).isEqualTo("BLACKLIST");
        assertThat(row.get("target_id")).isEqualTo("SHA256:" + hash);
        assertThat(row.get("actor_id")).isEqualTo(adminApi.adminId());
        assertThat(row.get("ip")).isNotNull();   // 테스트에서는 로컬 주소(127.0.0.1 또는 ::1)가 남는다
    }

    // IMP-10: 악성 지정 시 이미 받은 기기 조회
    @Test
    void 이미_받은_기기가_여러_대면_등록_응답에_전부_포함된다() {
        String hash = randomSha256();
        seedReceivedDevice(hash, "DESKTOP-A", Instant.now().minusSeconds(3600));
        seedReceivedDevice(hash, "DESKTOP-B", Instant.now());

        ResponseEntity<Map<String, Object>> response = post(hash, "뒤늦게 악성 확인됨");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(affectedDevices(response))
                .extracting(d -> d.get("hostname"))
                .containsExactlyInAnyOrder("DESKTOP-A", "DESKTOP-B");
    }

    @Test
    void BLOCKED로_끝난_이벤트는_실제로_못_받은_것이라_포함되지_않는다() {
        String hash = randomSha256();
        UUID deviceId = seedDevice("DESKTOP-BLOCKED");
        seedDownloadEvent(seedAgent(deviceId), hash, "BLOCKED", Instant.now());

        ResponseEntity<Map<String, Object>> response = post(hash, "사유");

        assertThat(affectedDevices(response)).isEmpty();
    }

    @Test
    void 받은_기기가_없으면_affectedDevices가_빈_배열이다() {
        String hash = randomSha256();

        ResponseEntity<Map<String, Object>> response = post(hash, "사유");

        assertThat(affectedDevices(response)).isNotNull().isEmpty();
    }

    @Test
    void 같은_기기가_여러_번_받았으면_한_번만_나오고_받은_횟수가_맞다() {
        String hash = randomSha256();
        UUID deviceId = seedDevice("DESKTOP-REPEAT");
        UUID agentId = seedAgent(deviceId);
        Instant first = Instant.now().minusSeconds(7200);
        Instant last = Instant.now();
        seedDownloadEvent(agentId, hash, "RELEASED", first);
        seedDownloadEvent(agentId, hash, "RELEASED", last);

        ResponseEntity<Map<String, Object>> response = post(hash, "사유");

        List<Map<String, Object>> devices = affectedDevices(response);
        assertThat(devices).hasSize(1);
        assertThat(devices.get(0).get("hostname")).isEqualTo("DESKTOP-REPEAT");
        assertThat(devices.get(0).get("receivedCount")).isEqualTo(2);
        Instant lastReceivedAt = Instant.parse((String) devices.get(0).get("lastReceivedAt"));
        assertThat(lastReceivedAt).isAfter(first);   // 두 번 중 더 늦은 시각이 나와야 한다

    }

    @Test
    void 해제됐던_해시를_재등록해도_affectedDevices가_똑같이_채워진다() {
        String hash = randomSha256();
        seedReceivedDevice(hash, "DESKTOP-REACTIVATE", Instant.now());
        post(hash, "처음 사유");
        jdbcTemplate.update(
                "UPDATE hash_blacklist SET is_active = false, deactivated_at = now() WHERE hash_value = ?",
                hash);

        ResponseEntity<Map<String, Object>> response = post(hash, "다시 등록한 사유");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(affectedDevices(response))
                .extracting(d -> d.get("hostname"))
                .containsExactly("DESKTOP-REACTIVATE");
    }

    @Test
    void BYPASSED로_받은_경우도_포함된다() {
        String hash = randomSha256();
        UUID deviceId = seedDevice("DESKTOP-BYPASSED");
        seedDownloadEvent(seedAgent(deviceId), hash, "BYPASSED", Instant.now());

        ResponseEntity<Map<String, Object>> response = post(hash, "사유");

        assertThat(affectedDevices(response))
                .extracting(d -> d.get("hostname"))
                .containsExactly("DESKTOP-BYPASSED");
    }
}