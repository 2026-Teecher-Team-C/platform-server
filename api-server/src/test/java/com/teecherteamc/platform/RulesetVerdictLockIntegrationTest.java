package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.teecherteamc.platform.ruleset.application.ActivateRulesetUseCase;
import com.teecherteamc.platform.ruleset.application.ChangeYaraRuleEnabledUseCase;
import com.teecherteamc.platform.ruleset.application.UploadRulesetUseCase;
import com.teecherteamc.platform.verdict.domain.ActiveRulesetKeyPort;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// 판정 저장 트랜잭션의 활성 룰셋 공유 잠금(FOR SHARE)이 활성화·활성 버전 룰 토글과 실제로 서로 기다리는지 본다.
@SpringBootTest
@AutoConfigureTestGrpcTransport
@Import(TestcontainersConfiguration.class)
class RulesetVerdictLockIntegrationTest {

    private static final String SOURCE = "rule Alpha_Rule { condition: true }\n";
    private static final long BLOCKED_MILLIS = 1000;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ActiveRulesetKeyPort activeRulesetKeyPort;

    @Autowired
    private ChangeYaraRuleEnabledUseCase changeYaraRuleEnabledUseCase;

    @Autowired
    private ActivateRulesetUseCase activateRulesetUseCase;

    @Autowired
    private UploadRulesetUseCase uploadRulesetUseCase;

    private UUID adminId;

    @BeforeEach
    void setUp() {
        FakeDetectionEngineService.reset();
        RulesetTestSupport.restoreSeed(jdbcTemplate);
        adminId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO admin_users (admin_id, username, password_hash, role, is_active) "
                + "VALUES (?, ?, 'x', 'ADMIN', true)", adminId, "lock-admin-" + adminId);
    }

    @AfterEach
    void tearDown() {
        RulesetTestSupport.restoreSeed(jdbcTemplate);
        jdbcTemplate.update("DELETE FROM admin_users WHERE admin_id = ?", adminId);
    }

    @Test
    void 판정_저장이_활성_룰셋을_잠그는_동안_활성_버전의_룰_토글은_기다린다() throws Exception {
        UUID ruleId = ruleId(1);

        assertWaitsForShareLock(() -> changeYaraRuleEnabledUseCase.change(1, ruleId, false, adminId, "127.0.0.1"));

        assertThat(jdbcTemplate.queryForObject("SELECT enabled FROM yara_rules WHERE rule_id = ?",
                Boolean.class, ruleId)).isFalse();
    }

    @Test
    void 판정_저장이_활성_룰셋을_잠그는_동안_활성화는_기다린다() throws Exception {
        int pending = uploadPending();

        assertWaitsForShareLock(() -> activateRulesetUseCase.activate(pending, adminId, "127.0.0.1"));

        assertThat(jdbcTemplate.queryForList("SELECT rulesets_version FROM yara_rulesets WHERE is_active",
                Integer.class)).containsExactly(pending);
    }

    @Test
    void 대기_버전의_룰_토글은_활성_룰셋_잠금을_기다리지_않는다() throws Exception {
        int pending = uploadPending();
        UUID ruleId = ruleId(pending);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = holdShareLock(locked, release);
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            changeYaraRuleEnabledUseCase.change(pending, ruleId, false, adminId, "127.0.0.1");
        } finally {
            release.countDown();
        }
        holder.get(10, TimeUnit.SECONDS);
    }

    /** 공유 잠금을 쥔 동안 action은 끝나지 못하고, 잠금을 놓으면 끝나야 한다. */
    private void assertWaitsForShareLock(Runnable action) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = holdShareLock(locked, release);
        CompletableFuture<Void> waiting;
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            waiting = CompletableFuture.runAsync(action);
            assertThatThrownBy(() -> waiting.get(BLOCKED_MILLIS, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
        } finally {
            release.countDown();
        }
        holder.get(10, TimeUnit.SECONDS);
        waiting.get(10, TimeUnit.SECONDS);
    }

    private CompletableFuture<Void> holdShareLock(CountDownLatch locked, CountDownLatch release) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            assertThat(activeRulesetKeyPort.lockActiveKey()).isPresent();
            locked.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
    }

    private int uploadPending() {
        return uploadRulesetUseCase.upload(new UploadRulesetUseCase.Command(
                "lock.yar", SOURCE.getBytes(StandardCharsets.UTF_8), adminId, "127.0.0.1")).getVersion();
    }

    private UUID ruleId(int version) {
        return jdbcTemplate.queryForObject("SELECT rule_id FROM yara_rules WHERE rulesets_version = ? "
                + "ORDER BY rule_name LIMIT 1", UUID.class, version);
    }
}
