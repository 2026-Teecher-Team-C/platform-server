package com.teecherteamc.platform;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 위협 대시보드 집계 테스트용 시드. 집계 API는 기간 안의 모든 이벤트를 세므로, 테스트마다 다른 테스트와 겹치지 않는
 * 과거 기간({@link #windowStart()})을 무작위로 잡아 그 안에만 이벤트를 넣는다.
 */
final class DashboardSeed {

    private final JdbcTemplate jdbc;
    private final Instant windowStart;

    DashboardSeed(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        // 2000-01-01 ~ 2019 사이 아무 날짜의 UTC 자정. 다른 테스트는 최근 시각을 쓰므로 겹치지 않는다.
        long days = ThreadLocalRandom.current().nextLong(0, 365L * 20);
        this.windowStart = Instant.parse("2000-01-01T00:00:00Z").plus(days, ChronoUnit.DAYS);
    }

    Instant windowStart() {
        return windowStart;
    }

    Instant at(long hours) {
        return windowStart.plus(hours, ChronoUnit.HOURS);
    }

    UUID agent(String hostname) {
        UUID deviceId = UUID.randomUUID();
        jdbc.update("INSERT INTO devices (device_id, hostname, os_platform) VALUES (?, ?, 'WINDOWS')",
                deviceId, hostname);
        UUID agentId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO agents (agent_id, device_id, agent_version, enrollment_token_hash) VALUES (?, ?, '0.0.1', ?)",
                agentId, deviceId, "hash-" + agentId);
        return agentId;
    }

    static String randomSha256() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }

    UUID event(UUID agentId, String sha256, String host, String decision, String source, Instant createdAt) {
        UUID eventId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO download_events
                    (event_id, agent_id, sha256, request_host, url, filename, pipeline_status,
                     decision, decision_source, held_at, decided_at, created_at)
                VALUES (?, ?, ?, ?, 'https://example.com/f', 'f.exe', ?, ?, ?, ?, ?, ?)
                """,
                // 판정 전 이벤트는 COMPLETED일 수 없다(ck_download_events_completed).
                eventId, agentId, sha256, host, decision == null ? "HELD" : "COMPLETED", decision, source,
                Timestamp.from(createdAt), Timestamp.from(createdAt), Timestamp.from(createdAt));
        return eventId;
    }

    /** sha256에 SUCCESS 분석과 룰 매칭을 단다. rules는 {이름, 심각도} 쌍. */
    void analysis(String sha256, String[]... rules) {
        jdbc.update("INSERT INTO file_verdicts (sha256, verdict, verdict_source) VALUES (?, 'MALICIOUS', 'ENGINE') "
                + "ON CONFLICT DO NOTHING", sha256);
        UUID analysisId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO analyses (analysis_id, sha256, rulesets_version, engine_version, status, verdict, duration_ms)
                VALUES (?, ?, 0, '1.0.0', 'SUCCESS', 'MALICIOUS', 100)
                """,
                analysisId, sha256);
        for (String[] rule : rules) {
            jdbc.update("INSERT INTO analysis_matches (match_id, analysis_id, rule_name, severity) VALUES (?, ?, ?, ?)",
                    UUID.randomUUID(), analysisId, rule[0], rule[1]);
        }
    }

    /** 룰 매칭 없는 분석 1건을 지정한 시각에 남긴다(엔진 상태 판단용). */
    void analysisAt(String status, Instant analyzedAt) {
        String sha256 = randomSha256();
        jdbc.update("INSERT INTO file_verdicts (sha256, verdict, verdict_source) VALUES (?, 'CLEAN', 'ENGINE')",
                sha256);
        jdbc.update(
                """
                INSERT INTO analyses (analysis_id, sha256, rulesets_version, engine_version, status, verdict,
                                      duration_ms, analyzed_at)
                VALUES (?, ?, 0, '1.0.0', ?, ?, 100, ?)
                """,
                UUID.randomUUID(), sha256, status, "SUCCESS".equals(status) ? "CLEAN" : "ERROR",
                Timestamp.from(analyzedAt));
    }

    UUID engineBlock(UUID agentId, String host, Instant createdAt, String[]... rules) {
        String sha256 = randomSha256();
        analysis(sha256, rules);
        return event(agentId, sha256, host, "BLOCKED", "ENGINE", createdAt);
    }

    UUID blacklistBlock(UUID agentId, String host, Instant createdAt, String severity) {
        String sha256 = randomSha256();
        jdbc.update("INSERT INTO hash_blacklist (hash_type, hash_value, reason, severity) VALUES ('SHA256', ?, 'bad', ?)",
                sha256, severity);
        return event(agentId, sha256, host, "BLOCKED", "BLACKLIST", createdAt);
    }

    UUID failClose(UUID agentId, String host, Instant createdAt) {
        return event(agentId, null, host, "FAIL_CLOSE", null, createdAt);
    }

    UUID released(UUID agentId, String host, Instant createdAt) {
        return event(agentId, randomSha256(), host, "RELEASED", "ENGINE", createdAt);
    }

    void acknowledge(UUID eventId) {
        UUID adminId = UUID.randomUUID();
        jdbc.update("INSERT INTO admin_users (admin_id, username, password_hash, role) VALUES (?, ?, 'x', 'ADMIN')",
                adminId, "ack-" + adminId);
        jdbc.update("INSERT INTO download_event_acknowledgements (event_id, acknowledged_by) VALUES (?, ?)",
                eventId, adminId);
    }
}
