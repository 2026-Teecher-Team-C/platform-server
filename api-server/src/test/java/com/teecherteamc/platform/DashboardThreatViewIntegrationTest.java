package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * download_event_threats 뷰(V6__dashboard_threat_view.sql) 검증. 위협 대시보드 API들이 전부
 * 이 뷰 하나를 조인해서 심각도·대표 룰·확인 여부를 계산하므로, 뷰 자체를 직접 SELECT해서 검증한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DashboardThreatViewIntegrationTest {

    private static final int DEFAULT_RULESETS_VERSION = 0;   // V2가 항상 시드해두는 행

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static String randomSha256() {
        byte[] bytes = new byte[32];
        ThreadLocalRandom.current().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private UUID seedDevice() {
        UUID deviceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO devices (device_id, hostname, os_platform) VALUES (?, 'test-host', 'WINDOWS')",
                deviceId);
        return deviceId;
    }

    private UUID seedAgent() {
        UUID agentId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO agents (agent_id, device_id, agent_version, enrollment_token_hash)
                VALUES (?, ?, '0.0.1', ?)
                """,
                agentId, seedDevice(), "hash-" + agentId);
        return agentId;
    }

    private UUID seedAdmin() {
        UUID adminId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO admin_users (admin_id, username, password_hash) VALUES (?, ?, 'unused')",
                adminId, "seed-" + adminId);
        return adminId;
    }

    private void seedFileVerdict(String sha256, String verdict) {
        jdbcTemplate.update(
                "INSERT INTO file_verdicts (sha256, verdict, verdict_source) VALUES (?, ?, 'ENGINE')",
                sha256, verdict);
    }

    private UUID seedDownloadEvent(String sha256, String decision, String decisionSource) {
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO download_events
                    (event_id, agent_id, sha256, request_host, url, pipeline_status,
                     decision, decision_source, held_at, decided_at)
                VALUES (?, ?, ?, 'example.com', 'https://example.com/f', 'COMPLETED', ?, ?, now(), now())
                """,
                eventId, seedAgent(), sha256, decision, decisionSource);
        return eventId;
    }

    private UUID seedAnalysis(String sha256, String status, Instant analyzedAt) {
        UUID analysisId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO analyses
                    (analysis_id, sha256, rulesets_version, engine_version, status, verdict, duration_ms, analyzed_at)
                VALUES (?, ?, ?, '1.0.0', ?, 'MALICIOUS', 100, ?)
                """,
                analysisId, sha256, DEFAULT_RULESETS_VERSION, status, Timestamp.from(analyzedAt));
        return analysisId;
    }

    private void seedMatch(UUID analysisId, String ruleName, String severity) {
        jdbcTemplate.update(
                "INSERT INTO analysis_matches (match_id, analysis_id, rule_name, severity) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), analysisId, ruleName, severity);
    }

    private void seedBlacklist(String sha256, String severity) {
        jdbcTemplate.update(
                """
                INSERT INTO hash_blacklist (hash_type, hash_value, reason, severity)
                VALUES ('SHA256', ?, 'known bad', ?)
                """,
                sha256, severity);
    }

    private Map<String, Object> threatRow(UUID eventId) {
        return jdbcTemplate.queryForMap("SELECT * FROM download_event_threats WHERE event_id = ?", eventId);
    }

    @Test
    void 룰_여러개가_매치되면_가장_심각한_룰이_대표이고_나머지는_extra로_잡힌다() {
        String sha256 = randomSha256();
        seedFileVerdict(sha256, "MALICIOUS");
        UUID analysisId = seedAnalysis(sha256, "SUCCESS", Instant.now());
        seedMatch(analysisId, "A", "HIGH");
        seedMatch(analysisId, "B", "CRITICAL");
        seedMatch(analysisId, "C", "CRITICAL");
        UUID eventId = seedDownloadEvent(sha256, "BLOCKED", "ENGINE");

        Map<String, Object> row = threatRow(eventId);

        assertThat(row.get("severity")).isEqualTo("CRITICAL");
        assertThat(row.get("top_rule_name")).isEqualTo("B");   // CRITICAL 동점이면 이름순
        assertThat(row.get("extra_rule_count")).isEqualTo(2L);   // COUNT(*) OVER(...)는 bigint라 Long으로 온다
        assertThat(row.get("reason_type")).isEqualTo("ENGINE");
    }

    @Test
    void 같은_sha256의_최신_분석이_TIMEOUT이면_그_전_SUCCESS_분석을_쓴다() {
        String sha256 = randomSha256();
        seedFileVerdict(sha256, "MALICIOUS");
        UUID oldAnalysisId = seedAnalysis(sha256, "SUCCESS", Instant.now().minusSeconds(3600));
        seedMatch(oldAnalysisId, "OLD_RULE", "MEDIUM");
        seedAnalysis(sha256, "TIMEOUT", Instant.now());   // 최신이지만 TIMEOUT이라 무시돼야 함
        UUID eventId = seedDownloadEvent(sha256, "BLOCKED", "ENGINE");

        Map<String, Object> row = threatRow(eventId);

        assertThat(row.get("top_rule_name")).isEqualTo("OLD_RULE");
        assertThat(row.get("severity")).isEqualTo("MEDIUM");
    }

    @Test
    void 블랙리스트_차단은_블랙리스트_심각도를_쓰고_대표_룰이_없다() {
        String sha256 = randomSha256();
        seedFileVerdict(sha256, "MALICIOUS");
        seedBlacklist(sha256, "HIGH");
        UUID eventId = seedDownloadEvent(sha256, "BLOCKED", "BLACKLIST");

        Map<String, Object> row = threatRow(eventId);

        assertThat(row.get("severity")).isEqualTo("HIGH");
        assertThat(row.get("reason_type")).isEqualTo("BLACKLIST");
        assertThat(row.get("top_rule_name")).isNull();
        assertThat(row.get("extra_rule_count")).isNull();
    }

    @Test
    void FAIL_CLOSE는_severity가_없다() {
        String sha256 = randomSha256();
        seedFileVerdict(sha256, "MALICIOUS");
        UUID eventId = seedDownloadEvent(sha256, "FAIL_CLOSE", null);

        Map<String, Object> row = threatRow(eventId);

        assertThat(row.get("severity")).isNull();
        assertThat(row.get("severity_rank")).isNull();
        assertThat(row.get("reason_type")).isEqualTo("FAIL_CLOSE");
    }

    @Test
    void 확인_행이_있으면_acknowledged가_true다() {
        String sha256 = randomSha256();
        seedFileVerdict(sha256, "CLEAN");
        UUID eventId = seedDownloadEvent(sha256, "RELEASED", "CACHE");

        assertThat(threatRow(eventId).get("acknowledged")).isEqualTo(false);

        jdbcTemplate.update(
                "INSERT INTO download_event_acknowledgements (event_id, acknowledged_by) VALUES (?, ?)",
                eventId, seedAdmin());

        assertThat(threatRow(eventId).get("acknowledged")).isEqualTo(true);
    }

    @Test
    void CACHE_판정도_ENGINE과_같은_계산_경로를_탄다() {
        String sha256 = randomSha256();
        seedFileVerdict(sha256, "MALICIOUS");
        UUID analysisId = seedAnalysis(sha256, "SUCCESS", Instant.now());
        seedMatch(analysisId, "CACHED_RULE", "CRITICAL");
        UUID eventId = seedDownloadEvent(sha256, "BLOCKED", "CACHE");

        Map<String, Object> row = threatRow(eventId);

        assertThat(row.get("severity")).isEqualTo("CRITICAL");
        assertThat(row.get("top_rule_name")).isEqualTo("CACHED_RULE");
        assertThat(row.get("reason_type")).isEqualTo("CACHE");
    }

    @Test
    void 화이트리스트로_통과한_이벤트는_위협_관련_컬럼이_전부_없다() {
        String sha256 = randomSha256();
        seedFileVerdict(sha256, "CLEAN");
        UUID eventId = seedDownloadEvent(sha256, "RELEASED", "WHITELIST");

        Map<String, Object> row = threatRow(eventId);

        assertThat(row.get("severity")).isNull();
        assertThat(row.get("reason_type")).isNull();
        assertThat(row.get("top_rule_name")).isNull();
        assertThat(row.get("extra_rule_count")).isNull();
    }
}
