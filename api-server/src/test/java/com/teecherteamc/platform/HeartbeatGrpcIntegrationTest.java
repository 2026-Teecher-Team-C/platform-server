package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.teecherteamc.platform.fleet.domain.TokenHash;
import com.teecherteamc.platform.proto.agent.v1.AgentServiceGrpc;
import com.teecherteamc.platform.proto.agent.v1.HeartbeatRequest;
import com.teecherteamc.platform.proto.agent.v1.HeartbeatResponse;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
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
class HeartbeatGrpcIntegrationTest {

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    @Autowired
    private AgentServiceGrpc.AgentServiceBlockingStub agentStub;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID seedAgent(String plainAgentToken, String status, Instant tokenExpiresAt) {
        UUID deviceId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO devices (device_id, hostname, os_platform) VALUES (?, 'host', 'WINDOWS')", deviceId);
        jdbcTemplate.update(
                """
                INSERT INTO agents (agent_id, device_id, agent_version, enrollment_token_hash, status,
                    agent_token_hash, agent_token_expires_at)
                VALUES (?, ?, '1.0.0', 'enrollment-hash', ?, ?, ?)
                """,
                agentId, deviceId, status, TokenHash.sha256Hex(plainAgentToken), java.sql.Timestamp.from(tokenExpiresAt));
        return agentId;
    }

    private AgentServiceGrpc.AgentServiceBlockingStub withBearer(String token) {
        Metadata metadata = new Metadata();
        metadata.put(AUTHORIZATION, "Bearer " + token);
        return agentStub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }

    @Test
    void 정상_하트비트는_last_heartbeat_at과_agent_version을_갱신한다() {
        UUID agentId = seedAgent("valid-agent-token", "ACTIVE", Instant.now().plusSeconds(3600));

        HeartbeatResponse response = withBearer("valid-agent-token")
                .heartbeat(HeartbeatRequest.newBuilder().setAgentVersion("1.1.0").build());

        assertThat(response.getServerTime().getSeconds()).isGreaterThan(0);

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM agents WHERE agent_id = ?", agentId);
        assertThat(row.get("agent_version")).isEqualTo("1.1.0");
        assertThat(row.get("last_heartbeat_at")).isNotNull();
    }

    @Test
    void authorization_헤더가_없으면_UNAUTHENTICATED로_거부된다() {
        seedAgent("some-token", "ACTIVE", Instant.now().plusSeconds(3600));

        assertThatThrownBy(() ->
                        agentStub.heartbeat(HeartbeatRequest.newBuilder().setAgentVersion("1.1.0").build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.UNAUTHENTICATED));
    }

    @Test
    void 잘못된_토큰은_UNAUTHENTICATED로_거부된다() {
        seedAgent("real-token", "ACTIVE", Instant.now().plusSeconds(3600));

        assertThatThrownBy(() -> withBearer("wrong-token")
                        .heartbeat(HeartbeatRequest.newBuilder().setAgentVersion("1.1.0").build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.UNAUTHENTICATED));
    }

    @Test
    void REVOKED_에이전트는_UNAUTHENTICATED로_거부된다() {
        seedAgent("revoked-token", "REVOKED", Instant.now().plusSeconds(3600));

        assertThatThrownBy(() -> withBearer("revoked-token")
                        .heartbeat(HeartbeatRequest.newBuilder().setAgentVersion("1.1.0").build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.UNAUTHENTICATED));
    }

    @Test
    void 만료된_agent_token은_UNAUTHENTICATED로_거부된다() {
        seedAgent("expired-agent-token", "ACTIVE", Instant.now().minusSeconds(60));

        assertThatThrownBy(() -> withBearer("expired-agent-token")
                        .heartbeat(HeartbeatRequest.newBuilder().setAgentVersion("1.1.0").build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.UNAUTHENTICATED));
    }

    @Test
    void agent_version이_비어있으면_INVALID_ARGUMENT로_거부된다() {
        seedAgent("blank-version-token", "ACTIVE", Instant.now().plusSeconds(3600));

        assertThatThrownBy(() -> withBearer("blank-version-token")
                        .heartbeat(HeartbeatRequest.newBuilder().setAgentVersion("").build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    @Test
    void agent_version이_32자를_넘으면_INVALID_ARGUMENT로_거부된다() {
        seedAgent("long-version-token", "ACTIVE", Instant.now().plusSeconds(3600));

        assertThatThrownBy(() -> withBearer("long-version-token")
                        .heartbeat(HeartbeatRequest.newBuilder().setAgentVersion("1".repeat(33)).build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
    }
}
