package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V23(LATERAL) download_event_threats 뷰가 이전 정의와 행 단위로 같은 결과를 내는지 확인한다.
 *
 * <p>이전 정의는 V17 SQL을 여기 고정해 둔다. V18이 sha256을 varchar로 바꾸며 블랙리스트 조인의 {@code ::text}
 * 캐스트만 뺐고(결과 동일), 지금 스키마에서 그대로 돌도록 캐스트 없는 V18 본문을 쓴다.
 * 여러 데이터 조합(BLACKLIST, ENGINE·CACHE 여러 룰, SIMILARITY, FAIL_CLOSE, 확인/미확인 등)을 넣고
 * 두 정의의 전체 열을 event_id 순으로 비교한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DashboardThreatViewLegacyEquivalenceIntegrationTest {

    /** V17 정의(V18에서 ::text 캐스트만 뺀 것). 바꾸지 말 것 — 비교 기준이다. */
    private static final String LEGACY_V17_VIEW_SQL =
            """
            WITH latest_success_analysis AS (
                SELECT DISTINCT ON (sha256) analysis_id, sha256
                FROM analyses
                WHERE status = 'SUCCESS'
                ORDER BY sha256, analyzed_at DESC
            ),
            ranked_matches AS (
                SELECT
                    lsa.sha256,
                    am.rule_name,
                    am.severity,
                    CASE am.severity
                        WHEN 'CRITICAL' THEN 4 WHEN 'HIGH' THEN 3 WHEN 'MEDIUM' THEN 2 WHEN 'LOW' THEN 1 ELSE 0
                    END AS severity_rank,
                    COUNT(*) OVER (PARTITION BY lsa.sha256) AS match_count,
                    ROW_NUMBER() OVER (
                        PARTITION BY lsa.sha256
                        ORDER BY
                            (CASE am.severity
                                WHEN 'CRITICAL' THEN 4 WHEN 'HIGH' THEN 3 WHEN 'MEDIUM' THEN 2 WHEN 'LOW' THEN 1 ELSE 0
                            END) DESC,
                            am.rule_name ASC
                    ) AS rule_rank
                FROM latest_success_analysis lsa
                JOIN analysis_matches am ON am.analysis_id = lsa.analysis_id
            ),
            top_match AS (
                SELECT sha256, rule_name AS top_rule_name, severity AS top_severity,
                       severity_rank AS top_severity_rank, match_count
                FROM ranked_matches
                WHERE rule_rank = 1
            )
            SELECT
                de.event_id,
                CASE
                    WHEN de.decision = 'FAIL_CLOSE' THEN NULL
                    WHEN de.decision = 'BLOCKED' AND de.decision_source = 'SIMILARITY' THEN 'MEDIUM'
                    WHEN de.decision_source = 'BLACKLIST' THEN bl.severity
                    WHEN de.decision_source IN ('ENGINE', 'CACHE') THEN tm.top_severity
                    ELSE NULL
                END AS severity,
                CASE
                    WHEN de.decision = 'FAIL_CLOSE' THEN NULL
                    WHEN de.decision = 'BLOCKED' AND de.decision_source = 'SIMILARITY' THEN 2
                    WHEN de.decision_source = 'BLACKLIST' THEN
                        CASE bl.severity
                            WHEN 'CRITICAL' THEN 4 WHEN 'HIGH' THEN 3 WHEN 'MEDIUM' THEN 2 WHEN 'LOW' THEN 1 ELSE NULL
                        END
                    WHEN de.decision_source IN ('ENGINE', 'CACHE') THEN tm.top_severity_rank
                    ELSE NULL
                END AS severity_rank,
                CASE WHEN de.decision_source IN ('ENGINE', 'CACHE') THEN tm.top_rule_name ELSE NULL END AS top_rule_name,
                CASE
                    WHEN de.decision_source IN ('ENGINE', 'CACHE') AND tm.match_count IS NOT NULL
                        THEN tm.match_count - 1
                    ELSE NULL
                END AS extra_rule_count,
                CASE
                    WHEN de.decision = 'FAIL_CLOSE' THEN 'FAIL_CLOSE'
                    WHEN de.decision_source IN ('ENGINE', 'CACHE', 'BLACKLIST', 'SIMILARITY') THEN de.decision_source
                    ELSE NULL
                END AS reason_type,
                (ack.event_id IS NOT NULL) AS acknowledged
            FROM download_events de
            LEFT JOIN top_match tm ON tm.sha256 = de.sha256
            LEFT JOIN hash_blacklist bl ON bl.hash_type = 'SHA256' AND bl.hash_value = de.sha256
            LEFT JOIN download_event_acknowledgements ack ON ack.event_id = de.event_id
            """;

    private static final int DEFAULT_RULESETS_VERSION = 0;   // V2가 항상 시드해 두는 행

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<UUID> eventIds = new ArrayList<>();

    private static String randomSha256() {
        byte[] bytes = new byte[32];
        ThreadLocalRandom.current().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private UUID seedAgent() {
        UUID deviceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO devices (device_id, hostname, os_platform) VALUES (?, 'eq-host', 'WINDOWS')", deviceId);
        UUID agentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO agents (agent_id, device_id, agent_version, enrollment_token_hash) VALUES (?, ?, '0.0.1', ?)",
                agentId, deviceId, "hash-" + agentId);
        return agentId;
    }

    private UUID seedAdmin() {
        UUID adminId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO admin_users (admin_id, username, password_hash) VALUES (?, ?, 'unused')",
                adminId, "eq-" + adminId);
        return adminId;
    }

    private String file(String verdict) {
        String sha256 = randomSha256();
        jdbcTemplate.update(
                "INSERT INTO file_verdicts (sha256, verdict, verdict_source) VALUES (?, ?, 'ENGINE')", sha256, verdict);
        return sha256;
    }

    private UUID analysis(String sha256, String status, Instant analyzedAt, String... ruleSeverityPairs) {
        UUID analysisId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO analyses
                    (analysis_id, sha256, rulesets_version, engine_version, status, verdict, duration_ms, analyzed_at)
                VALUES (?, ?, ?, '1.0.0', ?, 'MALICIOUS', 100, ?)
                """,
                analysisId, sha256, DEFAULT_RULESETS_VERSION, status, Timestamp.from(analyzedAt));
        for (int i = 0; i < ruleSeverityPairs.length; i += 2) {
            jdbcTemplate.update(
                    "INSERT INTO analysis_matches (match_id, analysis_id, rule_name, severity) VALUES (?, ?, ?, ?)",
                    UUID.randomUUID(), analysisId, ruleSeverityPairs[i], ruleSeverityPairs[i + 1]);
        }
        return analysisId;
    }

    private void blacklist(String sha256, String severity) {
        jdbcTemplate.update(
                "INSERT INTO hash_blacklist (hash_type, hash_value, reason, severity) VALUES ('SHA256', ?, 'eq', ?)",
                sha256, severity);
    }

    private UUID event(String sha256, String decision, String decisionSource, boolean acknowledged) {
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO download_events
                    (event_id, agent_id, sha256, request_host, url, pipeline_status,
                     decision, decision_source, held_at, decided_at)
                VALUES (?, ?, ?, 'example.com', 'https://example.com/f', ?, ?, ?, now(), now())
                """,
                eventId, seedAgent(), sha256, decision == null ? "HELD" : "COMPLETED", decision, decisionSource);
        if (acknowledged) {
            jdbcTemplate.update(
                    "INSERT INTO download_event_acknowledgements (event_id, acknowledged_by) VALUES (?, ?)",
                    eventId, seedAdmin());
        }
        eventIds.add(eventId);
        return eventId;
    }

    private List<Map<String, Object>> rows(String source) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM (" + source + ") v WHERE v.event_id = ANY (?) ORDER BY v.event_id",
                (Object) eventIds.toArray(new UUID[0]));
    }

    @Test
    void LATERAL_뷰는_V17_정의와_모든_조합에서_행_단위로_같다() {
        Instant now = Instant.now();

        // BLACKLIST: 심각도별, 블랙리스트 행이 없는 경우, 확인/미확인
        for (String sev : List.of("CRITICAL", "HIGH", "MEDIUM", "LOW")) {
            String sha = file("MALICIOUS");
            blacklist(sha, sev);
            event(sha, "BLOCKED", "BLACKLIST", false);
            event(sha, "BLOCKED", "BLACKLIST", true);
        }
        event(file("MALICIOUS"), "BLOCKED", "BLACKLIST", false);

        // ENGINE·CACHE 여러 룰(동점이면 이름순), 같은 sha의 여러 이벤트
        String multi = file("MALICIOUS");
        analysis(multi, "SUCCESS", now, "B_RULE", "CRITICAL", "A_RULE", "CRITICAL", "C_RULE", "HIGH", "D_RULE", "LOW");
        event(multi, "BLOCKED", "ENGINE", false);
        event(multi, "BLOCKED", "CACHE", true);
        event(multi, "BLOCKED", "CACHE", false);
        event(multi, "RELEASED", "CACHE", false);      // 판정과 무관하게 룰이 붙는다(이전 정의와 같게)
        event(multi, "FAIL_CLOSE", "ENGINE", false);   // FAIL_CLOSE는 심각도 없음, 룰 이름은 붙음
        event(multi, "BYPASSED", "POLICY", false);

        // 룰 하나, 심각도별
        for (String sev : List.of("HIGH", "MEDIUM", "LOW")) {
            String sha = file("MALICIOUS");
            analysis(sha, "SUCCESS", now, "ONLY_" + sev, sev);
            event(sha, "BLOCKED", "ENGINE", false);
        }

        // 최신 분석이 TIMEOUT이면 그 전 SUCCESS, SUCCESS 여러 건이면 최신 것
        String timeout = file("MALICIOUS");
        analysis(timeout, "SUCCESS", now.minusSeconds(7200), "OLDEST", "LOW");
        analysis(timeout, "SUCCESS", now.minusSeconds(3600), "OLD_X", "MEDIUM", "OLD_Y", "HIGH");
        analysis(timeout, "TIMEOUT", now);
        event(timeout, "BLOCKED", "ENGINE", false);

        // 최신 SUCCESS 분석에 매치 없음(이전 분석에는 있음), SUCCESS 분석 없음
        String noMatch = file("CLEAN");
        analysis(noMatch, "SUCCESS", now.minusSeconds(3600), "STALE", "CRITICAL");
        analysis(noMatch, "SUCCESS", now);
        event(noMatch, "RELEASED", "ENGINE", false);
        event(noMatch, "BLOCKED", "CACHE", false);
        String crashed = file("MALICIOUS");
        analysis(crashed, "CRASH", now, "NEVER", "CRITICAL");
        event(crashed, "BLOCKED", "ENGINE", false);

        // SIMILARITY: 차단(MEDIUM 고정), 룰·블랙리스트가 있어도 무시
        String similar = file("MALICIOUS");
        analysis(similar, "SUCCESS", now, "SIM_RULE", "CRITICAL");
        blacklist(similar, "CRITICAL");
        event(similar, "BLOCKED", "SIMILARITY", false);
        event(similar, "BLOCKED", "SIMILARITY", true);

        // FAIL_CLOSE(출처 없음), 화이트리스트, 판정 전(sha 없음)
        event(file("UNKNOWN"), "FAIL_CLOSE", null, false);
        event(file("UNKNOWN"), "FAIL_CLOSE", null, true);
        event(file("CLEAN"), "RELEASED", "WHITELIST", false);
        event(null, null, null, false);

        List<Map<String, Object>> legacy = rows(LEGACY_V17_VIEW_SQL);
        List<Map<String, Object>> current = rows("SELECT * FROM download_event_threats");

        assertThat(legacy).hasSize(eventIds.size());
        assertThat(current).isEqualTo(legacy);
        // 비교가 빈 값끼리만 같아서 통과하지 않게, 대표 열이 실제로 채워졌는지 본다
        assertThat(current).extracting(r -> r.get("top_rule_name")).contains("A_RULE", "OLD_Y", "ONLY_LOW");
        assertThat(current).extracting(r -> r.get("severity")).contains("CRITICAL", "HIGH", "MEDIUM", "LOW");
        assertThat(current).extracting(r -> r.get("extra_rule_count")).contains(3L, 1L, 0L);
        assertThat(current).extracting(r -> r.get("acknowledged")).contains(true, false);
    }
}
