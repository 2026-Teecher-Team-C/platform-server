package com.teecherteamc.platform.appeal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.teecherteamc.platform.TestcontainersConfiguration;
import com.teecherteamc.platform.appeal.application.AppealAlreadySubmittedException;
import com.teecherteamc.platform.appeal.application.AppealLinks;
import com.teecherteamc.platform.appeal.application.AppealNotFoundException;
import com.teecherteamc.platform.appeal.application.AppealView;
import com.teecherteamc.platform.appeal.application.GetAppealUseCase;
import com.teecherteamc.platform.appeal.application.InvalidAppealReasonException;
import com.teecherteamc.platform.appeal.application.SubmitAppealUseCase;
import com.teecherteamc.platform.verdict.domain.VerdictEvidence;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "platform.appeal.secret=0123456789abcdef0123456789abcdef",
        "platform.appeal.base-url=http://console.test"
})
class AppealUseCaseIntegrationTest {

    @Autowired
    private AppealLinks appealLinks;

    @Autowired
    private GetAppealUseCase getAppeal;

    @Autowired
    private SubmitAppealUseCase submitAppeal;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID seedAgent() {
        UUID deviceId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO devices (device_id, hostname, os_platform) VALUES (?, 'APPEAL-PC', 'WINDOWS')",
                deviceId);
        UUID agentId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO agents (agent_id, device_id, agent_version, enrollment_token_hash)
                VALUES (?, ?, '0.0.1', ?)
                """, agentId, deviceId, "hash-" + agentId);
        return agentId;
    }

    // 대시보드 테스트가 같은 DB를 쓰므로, 오늘 기간 집계에 섞이지 않게 과거 시각으로만 넣는다
    private void seedEvent(UUID eventId, UUID agentId, String filename, Instant decidedAt) {
        jdbcTemplate.update("""
                INSERT INTO download_events
                    (event_id, agent_id, request_host, url, filename, pipeline_status, decision, decision_source,
                     held_at, decided_at)
                VALUES (?, ?, 'bad.example', 'https://bad.example/f.exe', ?, 'COMPLETED', 'BLOCKED', 'SIMILARITY', ?, ?)
                """, eventId, agentId, filename, Timestamp.from(decidedAt), Timestamp.from(decidedAt));
    }

    private static String sha() {
        return UUID.randomUUID().toString().replace("-", "").repeat(2);
    }

    private String token(UUID eventId, UUID agentId) {
        return appealLinks.tokens().issue(eventId, sha(), agentId,
                VerdictEvidence.similarity(12, sha(), "MalwareBazaar: AgentTesla"));
    }

    @Test
    void get_without_event_row_returns_verdict_only() {
        UUID agentId = seedAgent();

        AppealView view = getAppeal.get(token(UUID.randomUUID(), agentId));

        assertThat(view.blockedAt()).isNull();
        assertThat(view.filename()).isNull();
        assertThat(view.appeal()).isNull();
        assertThat(view.verdict().kind()).isEqualTo("SIMILARITY");
        assertThat(view.verdict().threat()).isEqualTo("AgentTesla");
        assertThat(view.verdict().summary())
                .isEqualTo("알려진 악성코드와 매우 비슷한 파일입니다. 일부만 바꾼 변종일 수 있습니다.");
    }

    @Test
    void empty_threat_falls_back_to_kind_default_name() {
        UUID agentId = seedAgent();
        String token = appealLinks.tokens().issue(UUID.randomUUID(), sha(), agentId, VerdictEvidence.cache("MALICIOUS"));

        AppealView view = getAppeal.get(token);

        assertThat(view.verdict().kind()).isEqualTo("CACHE");
        assertThat(view.verdict().threat()).isEqualTo("이전 검사 결과");
        assertThat(view.verdict().summary()).isEqualTo("이전에 악성으로 판정된 파일입니다.");
    }

    @Test
    void get_with_event_row_returns_filename_and_blocked_at() {
        UUID agentId = seedAgent();
        UUID eventId = UUID.randomUUID();
        Instant decidedAt = Instant.parse("2020-03-01T10:12:03Z");
        seedEvent(eventId, agentId, "report.exe", decidedAt);

        AppealView view = getAppeal.get(token(eventId, agentId));

        assertThat(view.filename()).isEqualTo("report.exe");
        assertThat(view.blockedAt()).isEqualTo(decidedAt);
    }

    @Test
    void event_row_of_other_agent_is_ignored() {
        UUID owner = seedAgent();
        UUID other = seedAgent();
        UUID eventId = UUID.randomUUID();
        seedEvent(eventId, owner, "report.exe", Instant.parse("2020-03-01T10:12:03Z"));

        AppealView view = getAppeal.get(token(eventId, other));

        assertThat(view.filename()).isNull();
        assertThat(view.blockedAt()).isNull();
    }

    @Test
    void submit_creates_pending_and_audit_log() {
        UUID agentId = seedAgent();
        UUID eventId = UUID.randomUUID();
        String token = token(eventId, agentId);

        AppealView view = submitAppeal.submit(token, "  업무용 보고서입니다  ", "10.0.0.7");

        assertThat(view.appeal().status()).isEqualTo("PENDING");
        assertThat(view.appeal().reason()).isEqualTo("업무용 보고서입니다");
        assertThat(view.appeal().createdAt()).isNotNull();
        assertThat(view.appeal().decidedAt()).isNull();
        assertThat(view.appeal().decisionNote()).isNull();

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT appeal_id, status, reason, agent_id FROM appeals WHERE event_id = ?", eventId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("status")).isEqualTo("PENDING");
        assertThat(rows.get(0).get("reason")).isEqualTo("업무용 보고서입니다");
        assertThat(rows.get(0).get("agent_id")).isEqualTo(agentId);
        String appealId = rows.get(0).get("appeal_id").toString();

        List<Map<String, Object>> logs = jdbcTemplate.queryForList(
                "SELECT action, target_type, actor_id, host(ip_address) AS ip FROM audit_logs WHERE target_id = ?",
                appealId);
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).get("action")).isEqualTo("APPEAL_SUBMITTED");
        assertThat(logs.get(0).get("target_type")).isEqualTo("APPEAL");
        assertThat(logs.get(0).get("actor_id")).isNull();
        assertThat(logs.get(0).get("ip")).isEqualTo("10.0.0.7");

        assertThat(getAppeal.get(token).appeal().status()).isEqualTo("PENDING");
    }

    @Test
    void second_submit_conflicts() {
        String token = token(UUID.randomUUID(), seedAgent());
        submitAppeal.submit(token, "첫 번째", "10.0.0.7");

        assertThatThrownBy(() -> submitAppeal.submit(token, "두 번째", "10.0.0.7"))
                .isInstanceOf(AppealAlreadySubmittedException.class);
    }

    @Test
    void concurrent_submits_one_wins() throws Exception {
        UUID eventId = UUID.randomUUID();
        String token = token(eventId, seedAgent());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String reason = "동시 " + i;
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        submitAppeal.submit(token, reason, "10.0.0.7");
                        return "OK";
                    } catch (AppealAlreadySubmittedException e) {
                        return "CONFLICT";
                    }
                }));
            }
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> f : results) {
                outcomes.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(outcomes).containsExactlyInAnyOrder("OK", "CONFLICT");
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM appeals WHERE event_id = ?", Long.class, eventId))
                .isEqualTo(1L);
    }

    @Test
    void invalid_token_not_found() {
        assertThatThrownBy(() -> getAppeal.get("not-a-token")).isInstanceOf(AppealNotFoundException.class);
        assertThatThrownBy(() -> submitAppeal.submit("not-a-token", "사유", "10.0.0.7"))
                .isInstanceOf(AppealNotFoundException.class);
    }

    @Test
    void invalid_reason_rejected() {
        String token = token(UUID.randomUUID(), seedAgent());

        assertThatThrownBy(() -> submitAppeal.submit(token, "   ", "10.0.0.7"))
                .isInstanceOf(InvalidAppealReasonException.class);
        assertThatThrownBy(() -> submitAppeal.submit(token, null, "10.0.0.7"))
                .isInstanceOf(InvalidAppealReasonException.class);
        assertThatThrownBy(() -> submitAppeal.submit(token, "가".repeat(1001), "10.0.0.7"))
                .isInstanceOf(InvalidAppealReasonException.class);
        // 코드 포인트 기준: 서로게이트 쌍 이모지 1000개(char 2000개)는 통과한다
        AppealView view = submitAppeal.submit(token, "😀".repeat(1000), "10.0.0.7");
        assertThat(view.appeal().reason().codePointCount(0, view.appeal().reason().length())).isEqualTo(1000);
    }

    @Test
    void migration_check_rejects_pending_with_decided_at() {
        UUID agentId = seedAgent();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO appeals (appeal_id, event_id, sha256, agent_id, reason, status, decided_at)
                VALUES (?, ?, ?, ?, '사유', 'PENDING', now())
                """, UUID.randomUUID(), UUID.randomUUID(), sha(), agentId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
