package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.teecherteamc.platform.dashboard.domain.DashboardPeriod;
import com.teecherteamc.platform.dashboard.domain.DashboardWidgetsPort;
import com.teecherteamc.platform.dashboard.domain.ThreatRow;
import com.teecherteamc.platform.dashboard.domain.UnacknowledgedBlockRow;
import com.teecherteamc.platform.dashboard.domain.UnacknowledgedBlocksPort;
import com.teecherteamc.platform.event.domain.DownloadEventListFilter;
import com.teecherteamc.platform.event.domain.ListDownloadEventsPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 1단계(V24) 쿼리 최적화가 이전 SQL과 완전히 같은 결과를 내는지 검증한다.
 *
 * <ul>
 *   <li>{@code ListDownloadEventsPort.count()}: 뷰 조인 생략 시에도 건수 동일</li>
 *   <li>{@code UnacknowledgedBlocksPort.list()/count()}: 뷰 기반 SQL과 행·건수 동일</li>
 *   <li>{@code DashboardWidgetsPort.threats()}: 선집계 SQL과 행·affected_agents·block_count 동일</li>
 * </ul>
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DashboardStage1EquivalenceIntegrationTest {

    private static final String LEGACY_UNACKED_WHERE =
            """
            FROM download_events e
            JOIN download_event_threats t ON t.event_id = e.event_id
            JOIN agents a ON a.agent_id = e.agent_id
            JOIN devices d ON d.device_id = a.device_id
            WHERE e.decision IN ('BLOCKED', 'FAIL_CLOSE')
              AND t.severity_rank >= 3
              AND NOT t.acknowledged
            """;

    private static final String LEGACY_THREATS_SQL =
            """
            WITH blocked AS (
                SELECT e.agent_id, e.sha256, e.decision_source, e.created_at
                FROM download_events e
                JOIN agents a ON a.agent_id = e.agent_id
                JOIN devices d ON d.device_id = a.device_id
                WHERE e.decision = 'BLOCKED'
                  AND e.created_at >= ? AND e.created_at < ?
            ),
            latest_success_analysis AS (
                SELECT DISTINCT ON (an.sha256) an.analysis_id, an.sha256
                FROM analyses an
                WHERE an.status = 'SUCCESS'
                  AND an.sha256 IN (SELECT sha256 FROM blocked WHERE decision_source IN ('ENGINE', 'CACHE'))
                ORDER BY an.sha256, an.analyzed_at DESC
            ),
            hits AS (
                SELECT 'RULE' AS kind, am.rule_name, am.severity, b.agent_id, b.created_at
                FROM blocked b
                JOIN latest_success_analysis l ON l.sha256 = b.sha256
                JOIN analysis_matches am ON am.analysis_id = l.analysis_id
                WHERE b.decision_source IN ('ENGINE', 'CACHE')
                UNION ALL
                SELECT 'BLACKLIST', NULL, bl.severity, b.agent_id, b.created_at
                FROM blocked b
                JOIN hash_blacklist bl ON bl.hash_type = 'SHA256' AND bl.hash_value = b.sha256::text
                WHERE b.decision_source = 'BLACKLIST'
            )
            SELECT kind, rule_name,
                   (ARRAY['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'])[MAX(
                       CASE severity WHEN 'CRITICAL' THEN 4 WHEN 'HIGH' THEN 3 WHEN 'MEDIUM' THEN 2 ELSE 1 END)]
                       AS severity,
                   COUNT(DISTINCT agent_id) AS affected_agents,
                   COUNT(*) AS block_count,
                   MIN(created_at) AS first_seen_at,
                   MAX(created_at) AS last_seen_at
            FROM hits
            GROUP BY kind, rule_name
            ORDER BY last_seen_at DESC, rule_name NULLS FIRST
            LIMIT ?
            """;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UnacknowledgedBlocksPort unacknowledgedBlocksPort;

    @Autowired
    private DashboardWidgetsPort dashboardWidgetsPort;

    @Autowired
    private ListDownloadEventsPort listDownloadEventsPort;

    @Test
    void 일단계_최적화_쿼리가_이전_SQL과_같은_결과를_낸다() {
        UUID adminId = UUID.randomUUID();
        jdbc.update("INSERT INTO admin_users (admin_id, username, password_hash, role) VALUES (?, ?, 'h', 'ADMIN')",
                adminId, "s1-" + adminId);

        UUID dev1 = UUID.randomUUID();
        UUID dev2 = UUID.randomUUID();
        UUID ag1 = UUID.randomUUID();
        UUID ag2 = UUID.randomUUID();
        insertAgent(dev1, ag1, "host-s1-1");
        insertAgent(dev2, ag2, "host-s1-2");

        String shaRuleHighA = randomSha256();
        String shaRuleHighB = randomSha256();
        String shaRuleMed = randomSha256();
        String shaBlCrit = randomSha256();
        String shaBlMed = randomSha256();
        String shaSim = randomSha256();
        String shaFailClose = randomSha256();
        String shaReleased = randomSha256();

        Instant t0 = Instant.parse("2026-11-27T01:00:00Z");
        // 두 서로 다른 sha256이 같은 룰(RuleShared)을 공유하고, ag1이 둘 다 맞았을 때 affected_agents=1이어야 한다.
        insertAnalysisWithMatches(shaRuleHighA, t0, List.of(new RuleMatch("RuleShared", "HIGH"), new RuleMatch("RuleExtra", "LOW")));
        insertAnalysisWithMatches(shaRuleHighB, t0.plusSeconds(10), List.of(new RuleMatch("RuleShared", "HIGH")));
        insertAnalysisWithMatches(shaRuleMed, t0.plusSeconds(20), List.of(new RuleMatch("RuleMed", "MEDIUM")));

        jdbc.update("INSERT INTO hash_blacklist (hash_type, hash_value, reason, severity, source) VALUES ('SHA256', ?, 'test', 'CRITICAL', 'IMPORT')",
                shaBlCrit);
        jdbc.update("INSERT INTO hash_blacklist (hash_type, hash_value, reason, severity, source) VALUES ('SHA256', ?, 'test', 'MEDIUM', 'IMPORT')",
                shaBlMed);

        UUID evUnackedRule1 = insertEvent(ag1, shaRuleHighA, "BLOCKED", "ENGINE", t0.plusSeconds(100));
        insertEvent(ag1, shaRuleHighB, "BLOCKED", "CACHE", t0.plusSeconds(110));
        insertEvent(ag2, shaRuleHighB, "BLOCKED", "ENGINE", t0.plusSeconds(120));
        UUID evAckedRule = insertEvent(ag2, shaRuleHighA, "BLOCKED", "ENGINE", t0.plusSeconds(130));
        jdbc.update("INSERT INTO download_event_acknowledgements (event_id, acknowledged_by) VALUES (?, ?)", evAckedRule, adminId);

        insertEvent(ag1, shaRuleMed, "BLOCKED", "ENGINE", t0.plusSeconds(140));
        insertEvent(ag1, shaBlCrit, "BLOCKED", "BLACKLIST", t0.plusSeconds(150));
        insertEvent(ag2, shaBlMed, "BLOCKED", "BLACKLIST", t0.plusSeconds(160));
        insertEvent(ag1, shaSim, "BLOCKED", "SIMILARITY", t0.plusSeconds(170));
        insertEvent(ag1, shaFailClose, "FAIL_CLOSE", null, t0.plusSeconds(180));
        insertEvent(ag1, shaReleased, "RELEASED", "CACHE", t0.plusSeconds(190));

        // 1) UnacknowledgedBlocksPort count() & list()
        long legacyCount = jdbc.queryForObject("SELECT COUNT(*) " + LEGACY_UNACKED_WHERE, Long.class);
        assertThat(unacknowledgedBlocksPort.count()).isEqualTo(legacyCount);

        List<UnacknowledgedBlockRow> legacyList = jdbc.query(
                "SELECT e.event_id, e.created_at, e.agent_id, d.hostname, e.filename, t.reason_type, "
                        + "t.top_rule_name, t.extra_rule_count, t.severity "
                        + LEGACY_UNACKED_WHERE
                        + " ORDER BY e.created_at DESC, e.event_id DESC LIMIT 20 OFFSET 0",
                (rs, rowNum) -> new UnacknowledgedBlockRow(
                        UUID.fromString(rs.getString("event_id")),
                        rs.getTimestamp("created_at").toInstant(),
                        UUID.fromString(rs.getString("agent_id")),
                        rs.getString("hostname"),
                        rs.getString("filename"),
                        "BLACKLIST".equals(rs.getString("reason_type")) ? "BLACKLIST" : "RULE",
                        rs.getString("top_rule_name"),
                        rs.getInt("extra_rule_count"),
                        rs.getString("severity")));
        assertThat(unacknowledgedBlocksPort.list(0, 20)).isEqualTo(legacyList);
        assertThat(unacknowledgedBlocksPort.list(1, 2)).isEqualTo(legacyList.subList(1, Math.min(3, legacyList.size())));
        assertThat(unacknowledgedBlocksPort.list(0, 20)).extracting(UnacknowledgedBlockRow::eventId).contains(evUnackedRule1);

        // 2) DashboardWidgetsPort.threats()
        DashboardPeriod period = new DashboardPeriod(t0.plusSeconds(90), t0.plusSeconds(200));
        List<ThreatRow> legacyThreats = jdbc.query(
                LEGACY_THREATS_SQL,
                (rs, rowNum) -> new ThreatRow(
                        rs.getString("kind"),
                        rs.getString("rule_name"),
                        rs.getString("severity"),
                        rs.getLong("affected_agents"),
                        rs.getLong("block_count"),
                        rs.getTimestamp("first_seen_at").toInstant(),
                        rs.getTimestamp("last_seen_at").toInstant()),
                Timestamp.from(period.from()), Timestamp.from(period.to()), 20);
        assertThat(dashboardWidgetsPort.threats(period, 20)).isEqualTo(legacyThreats);

        // 3) ListDownloadEventsPort.count() — 뷰 조인 생략 시에도 list()와 일치
        var filter = new DownloadEventListFilter(null, List.of("BLOCKED", "FAIL_CLOSE"), period.from(), period.to(), null, null);
        assertThat(listDownloadEventsPort.count(filter)).isEqualTo(listDownloadEventsPort.list(filter, 0, 100).size());
    }

    private void insertAgent(UUID deviceId, UUID agentId, String hostname) {
        jdbc.update("INSERT INTO devices (device_id, hostname, os_platform) VALUES (?, ?, 'WINDOWS')", deviceId, hostname);
        jdbc.update("INSERT INTO agents (agent_id, device_id, agent_version, enrollment_token_hash) VALUES (?, ?, '1.0', ?)",
                agentId, deviceId, "tok-" + agentId);
    }

    private UUID insertEvent(UUID agentId, String sha256, String decision, String source, Instant createdAt) {
        jdbc.update("INSERT INTO file_verdicts (sha256, verdict, verdict_source) VALUES (?, 'MALICIOUS', 'ENGINE') ON CONFLICT DO NOTHING", sha256);
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO download_events
                    (event_id, agent_id, sha256, filename, file_size, request_host, url,
                     pipeline_status, decision, decision_source, held_at, decided_at, created_at)
                VALUES (?, ?, ?, 'sample.exe', 1024, 'example.com', 'https://example.com/f',
                        'COMPLETED', ?, ?, ?, ?, ?)
                """, eventId, agentId, sha256, decision, source,
                Timestamp.from(createdAt), Timestamp.from(createdAt), Timestamp.from(createdAt));
        return eventId;
    }

    private void insertAnalysisWithMatches(String sha256, Instant analyzedAt, List<RuleMatch> matches) {
        jdbc.update("INSERT INTO file_verdicts (sha256, verdict, verdict_source) VALUES (?, 'MALICIOUS', 'ENGINE') ON CONFLICT DO NOTHING", sha256);
        UUID analysisId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO analyses
                    (analysis_id, sha256, rulesets_version, engine_version, status, verdict, duration_ms, analyzed_at)
                VALUES (?, ?, '0000000000000000000000000000000000000000000000000000000000000000',
                        '1.0.0', 'SUCCESS', 'MALICIOUS', 100, ?)
                """, analysisId, sha256, Timestamp.from(analyzedAt));
        for (RuleMatch m : matches) {
            jdbc.update("""
                    INSERT INTO analysis_matches (match_id, analysis_id, rule_name, severity)
                    VALUES (?, ?, ?, ?)
                    """, UUID.randomUUID(), analysisId, m.name(), m.severity());
        }
    }

    private static String randomSha256() {
        byte[] b = new byte[32];
        ThreadLocalRandom.current().nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private record RuleMatch(String name, String severity) {}
}
