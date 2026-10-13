package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.teecherteamc.platform.fleet.domain.TokenHash;
import com.teecherteamc.platform.proto.agent.v1.AgentServiceGrpc;
import com.teecherteamc.platform.proto.agent.v1.OsPlatform;
import com.teecherteamc.platform.proto.agent.v1.RegisterAgentRequest;
import com.teecherteamc.platform.proto.agent.v1.RegisterAgentResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.grpc.client.ImportGrpcClients;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@AutoConfigureTestGrpcTransport
@ImportGrpcClients(types = AgentServiceGrpc.AgentServiceBlockingStub.class)
@Import(TestcontainersConfiguration.class)
class RegisterAgentGrpcIntegrationTest {

    @Autowired
    private AgentServiceGrpc.AgentServiceBlockingStub agentStub;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID existingAgentId;

    @BeforeEach
    void seedExistingDeviceAndAgent() {
        // 이미사용된 토큰 fixture의 redeemed_by_agent_id FK를 만족시키는 용도.
        UUID deviceId = UUID.randomUUID();
        existingAgentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO devices (device_id, hostname, os_platform) VALUES (?, 'existing-host', 'WINDOWS')",
                deviceId);
        jdbcTemplate.update(
                """
                INSERT INTO agents (agent_id, device_id, agent_version, enrollment_token_hash)
                VALUES (?, ?, '0.0.1', 'existing-hash')
                """,
                existingAgentId, deviceId);
    }

    private void seedEnrollmentToken(String plainToken, Instant expiresAt, boolean redeemed) {
        jdbcTemplate.update(
                """
                INSERT INTO agent_enrollment_tokens (enrollment_token_id, enrollment_token_hash, expires_at,
                    redeemed_at, redeemed_by_agent_id)
                VALUES (?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                TokenHash.sha256Hex(plainToken),
                java.sql.Timestamp.from(expiresAt),
                redeemed ? java.sql.Timestamp.from(Instant.now()) : null,
                redeemed ? existingAgentId : null);
    }

    @Test
    void 정상_등록은_새_device와_agent를_만들고_토큰을_redeem한다() {
        seedEnrollmentToken("valid-token", Instant.now().plusSeconds(3600), false);

        RegisterAgentResponse response = agentStub.registerAgent(RegisterAgentRequest.newBuilder()
                .setEnrollmentToken("valid-token")
                .setHostname("new-host")
                .setOsPlatform(OsPlatform.OS_PLATFORM_LINUX)
                .setAgentVersion("1.0.0")
                .setHardwareUuid("hw-uuid-1")
                .build());

        assertThat(response.getAgentId()).isNotBlank();
        assertThat(response.getAgentToken()).isNotBlank();
        assertThat(response.getTokenExpiresAt().getSeconds()).isGreaterThan(Instant.now().getEpochSecond());

        UUID agentId = UUID.fromString(response.getAgentId());
        Map<String, Object> agentRow =
                jdbcTemplate.queryForMap("SELECT * FROM agents WHERE agent_id = ?", agentId);
        assertThat(agentRow.get("agent_version")).isEqualTo("1.0.0");
        assertThat(agentRow.get("status")).isEqualTo("ACTIVE");
        assertThat(agentRow.get("agent_token_hash")).isNotNull();

        Map<String, Object> deviceRow = jdbcTemplate.queryForMap(
                "SELECT * FROM devices WHERE device_id = ?", agentRow.get("device_id"));
        assertThat(deviceRow.get("hostname")).isEqualTo("new-host");
        assertThat(deviceRow.get("os_platform")).isEqualTo("LINUX");
        assertThat(deviceRow.get("hardware_uuid")).isEqualTo("hw-uuid-1");

        Map<String, Object> tokenRow = jdbcTemplate.queryForMap(
                "SELECT * FROM agent_enrollment_tokens WHERE enrollment_token_hash = ?",
                TokenHash.sha256Hex("valid-token"));
        assertThat(tokenRow.get("redeemed_by_agent_id")).isEqualTo(agentId);
        assertThat(tokenRow.get("redeemed_at")).isNotNull();
    }

    @Test
    void 만료된_토큰은_UNAUTHENTICATED로_거부된다() {
        seedEnrollmentToken("expired-token", Instant.now().minusSeconds(60), false);

        assertThatThrownBy(() -> agentStub.registerAgent(RegisterAgentRequest.newBuilder()
                        .setEnrollmentToken("expired-token")
                        .setHostname("host")
                        .setOsPlatform(OsPlatform.OS_PLATFORM_WINDOWS)
                        .setAgentVersion("1.0.0")
                        .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.UNAUTHENTICATED));
    }

    @Test
    void 이미_사용된_토큰은_UNAUTHENTICATED로_거부된다() {
        seedEnrollmentToken("used-token", Instant.now().plusSeconds(3600), true);

        assertThatThrownBy(() -> agentStub.registerAgent(RegisterAgentRequest.newBuilder()
                        .setEnrollmentToken("used-token")
                        .setHostname("host")
                        .setOsPlatform(OsPlatform.OS_PLATFORM_WINDOWS)
                        .setAgentVersion("1.0.0")
                        .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.UNAUTHENTICATED));
    }

    @Test
    void 존재하지_않는_토큰은_UNAUTHENTICATED로_거부된다() {
        assertThatThrownBy(() -> agentStub.registerAgent(RegisterAgentRequest.newBuilder()
                        .setEnrollmentToken("no-such-token")
                        .setHostname("host")
                        .setOsPlatform(OsPlatform.OS_PLATFORM_WINDOWS)
                        .setAgentVersion("1.0.0")
                        .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.UNAUTHENTICATED));
    }

    @Test
    void hostname이_비어있으면_INVALID_ARGUMENT로_거부된다() {
        seedEnrollmentToken("blank-hostname-token", Instant.now().plusSeconds(3600), false);

        assertThatThrownBy(() -> agentStub.registerAgent(RegisterAgentRequest.newBuilder()
                        .setEnrollmentToken("blank-hostname-token")
                        .setHostname("")
                        .setOsPlatform(OsPlatform.OS_PLATFORM_WINDOWS)
                        .setAgentVersion("1.0.0")
                        .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    @Test
    void os_platform이_UNSPECIFIED면_INVALID_ARGUMENT로_거부된다() {
        seedEnrollmentToken("unspecified-os-token", Instant.now().plusSeconds(3600), false);

        assertThatThrownBy(() -> agentStub.registerAgent(RegisterAgentRequest.newBuilder()
                        .setEnrollmentToken("unspecified-os-token")
                        .setHostname("host")
                        .setAgentVersion("1.0.0")
                        .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    @Test
    void hostname이_255자를_넘으면_INVALID_ARGUMENT로_거부된다() {
        seedEnrollmentToken("long-hostname-token", Instant.now().plusSeconds(3600), false);

        assertThatThrownBy(() -> agentStub.registerAgent(RegisterAgentRequest.newBuilder()
                        .setEnrollmentToken("long-hostname-token")
                        .setHostname("a".repeat(256))
                        .setOsPlatform(OsPlatform.OS_PLATFORM_WINDOWS)
                        .setAgentVersion("1.0.0")
                        .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    @Test
    void agent_version이_32자를_넘으면_INVALID_ARGUMENT로_거부된다() {
        seedEnrollmentToken("long-version-token", Instant.now().plusSeconds(3600), false);

        assertThatThrownBy(() -> agentStub.registerAgent(RegisterAgentRequest.newBuilder()
                        .setEnrollmentToken("long-version-token")
                        .setHostname("host")
                        .setOsPlatform(OsPlatform.OS_PLATFORM_WINDOWS)
                        .setAgentVersion("1".repeat(33))
                        .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    @Test
    void hardware_uuid가_64자를_넘으면_INVALID_ARGUMENT로_거부된다() {
        seedEnrollmentToken("long-hwuuid-token", Instant.now().plusSeconds(3600), false);

        assertThatThrownBy(() -> agentStub.registerAgent(RegisterAgentRequest.newBuilder()
                        .setEnrollmentToken("long-hwuuid-token")
                        .setHostname("host")
                        .setOsPlatform(OsPlatform.OS_PLATFORM_WINDOWS)
                        .setAgentVersion("1.0.0")
                        .setHardwareUuid("h".repeat(65))
                        .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    @Test
    void 동시에_같은_토큰으로_등록하면_하나만_성공한다() throws InterruptedException {
        seedEnrollmentToken("race-token", Instant.now().plusSeconds(3600), false);

        int threads = 5;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger rejectedCount = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Runnable> tasks = List.of(
                    attemptRegister("race-token", ready, start, successCount, rejectedCount),
                    attemptRegister("race-token", ready, start, successCount, rejectedCount),
                    attemptRegister("race-token", ready, start, successCount, rejectedCount),
                    attemptRegister("race-token", ready, start, successCount, rejectedCount),
                    attemptRegister("race-token", ready, start, successCount, rejectedCount));
            tasks.forEach(executor::execute);

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(rejectedCount.get()).isEqualTo(threads - 1);

        Integer agentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agents WHERE enrollment_token_hash = ?",
                Integer.class, TokenHash.sha256Hex("race-token"));
        assertThat(agentCount).isEqualTo(1);
    }

    private Runnable attemptRegister(
            String token, CountDownLatch ready, CountDownLatch start, AtomicInteger successCount,
            AtomicInteger rejectedCount) {
        return () -> {
            ready.countDown();
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                agentStub.registerAgent(RegisterAgentRequest.newBuilder()
                        .setEnrollmentToken(token)
                        .setHostname("race-host")
                        .setOsPlatform(OsPlatform.OS_PLATFORM_WINDOWS)
                        .setAgentVersion("1.0.0")
                        .build());
                successCount.incrementAndGet();
            } catch (StatusRuntimeException e) {
                if (e.getStatus().getCode() == Status.Code.UNAUTHENTICATED) {
                    rejectedCount.incrementAndGet();
                } else {
                    throw e;
                }
            }
        };
    }
}
