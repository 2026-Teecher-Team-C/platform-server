package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.teecherteamc.platform.fleet.domain.TokenHash;
import com.teecherteamc.platform.policy.domain.BypassDomainRow;
import com.teecherteamc.platform.policy.domain.ListBypassDomainsPort;
import com.teecherteamc.platform.proto.agent.v1.AgentServiceGrpc;
import com.teecherteamc.platform.proto.agent.v1.BypassCategory;
import com.teecherteamc.platform.proto.agent.v1.BypassHost;
import com.teecherteamc.platform.proto.agent.v1.GetPolicyRequest;
import com.teecherteamc.platform.proto.agent.v1.GetPolicyResponse;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
class GetPolicyGrpcIntegrationTest {

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    // agents는 비우지 않으므로 테스트마다 다른 토큰을 써야 같은 해시의 행이 겹치지 않는다.
    private String token;

    @Autowired
    private AgentServiceGrpc.AgentServiceBlockingStub agentStub;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ListBypassDomainsPort listBypassDomainsPort;

    @BeforeEach
    void 준비한다() {
        jdbcTemplate.update("DELETE FROM bypass_domains");
        token = "get-policy-" + UUID.randomUUID();
        seedAgent(token);
    }

    private void seedAgent(String plainAgentToken) {
        UUID deviceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO devices (device_id, hostname, os_platform) VALUES (?, 'host', 'WINDOWS')", deviceId);
        jdbcTemplate.update(
                """
                INSERT INTO agents (agent_id, device_id, agent_version, enrollment_token_hash, status,
                    agent_token_hash, agent_token_expires_at)
                VALUES (?, ?, '1.0.0', 'enrollment-hash', 'ACTIVE', ?, ?)
                """,
                UUID.randomUUID(),
                deviceId,
                TokenHash.sha256Hex(plainAgentToken),
                Timestamp.from(Instant.now().plusSeconds(3600)));
    }

    private void seedBypass(String host, String category, boolean active) {
        jdbcTemplate.update(
                """
                INSERT INTO bypass_domains (bypass_id, domain_pattern, category, reason, is_active, created_at)
                VALUES (?, ?, ?, 'test reason', ?, ?)
                """,
                UUID.randomUUID(), host, category, active, Timestamp.from(Instant.now()));
    }

    private GetPolicyResponse getPolicy() {
        Metadata metadata = new Metadata();
        metadata.put(AUTHORIZATION, "Bearer " + token);
        return agentStub
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                .getPolicy(GetPolicyRequest.getDefaultInstance());
    }

    private static List<String> hosts(GetPolicyResponse response) {
        return response.getBypassHostsList().stream().map(BypassHost::getHost).toList();
    }

    @Test
    void 활성_바이패스_호스트만_내려가고_해제된_호스트는_빠진다() {
        seedBypass("active.example.com", "PINNED", true);
        seedBypass("inactive.example.com", "PINNED", false);

        GetPolicyResponse response = getPolicy();

        assertThat(hosts(response)).containsExactly("active.example.com");
    }

    @Test
    void 호스트_오름차순으로_내려간다() {
        seedBypass("c.example.com", "PINNED", true);
        seedBypass("a.example.com", "PINNED", true);
        seedBypass("b.example.com", "PINNED", true);

        assertThat(hosts(getPolicy())).containsExactly("a.example.com", "b.example.com", "c.example.com");
    }

    @Test
    void category가_각각_맞는_proto_enum으로_바뀐다() {
        seedBypass("pinned.example.com", "PINNED", true);
        seedBypass("update.example.com", "SECURITY_UPDATE", true);

        List<BypassHost> hosts = getPolicy().getBypassHostsList();

        assertThat(hosts).extracting(BypassHost::getHost, BypassHost::getCategory)
                .containsExactly(
                        tuple("pinned.example.com", BypassCategory.BYPASS_CATEGORY_PINNED),
                        tuple("update.example.com", BypassCategory.BYPASS_CATEGORY_SECURITY_UPDATE));
    }

    @Test
    void 활성_호스트가_없으면_빈_목록으로_정상_응답한다() {
        GetPolicyResponse response = getPolicy();

        assertThat(response.getBypassHostsList()).isEmpty();
        assertThat(response.getFileTypePoliciesList()).isEmpty();
    }

    @Test
    void 콘솔_목록의_활성_기본_집합과_같은_호스트가_내려간다() {
        seedBypass("one.example.com", "PINNED", true);
        seedBypass("two.example.com", "SECURITY_UPDATE", true);
        seedBypass("gone.example.com", "PINNED", false);

        List<String> listed = listBypassDomainsPort.list(true, null, null, 0, 100).stream()
                .map(BypassDomainRow::host)
                .toList();

        assertThat(hosts(getPolicy())).containsExactlyInAnyOrderElementsOf(listed);
    }

    @Test
    void file_type_policies는_이번에는_비어_있다() {
        seedBypass("any.example.com", "PINNED", true);

        assertThat(getPolicy().getFileTypePoliciesList()).isEmpty();
    }

    @Test
    void authorization_헤더가_없으면_UNAUTHENTICATED로_거부된다() {
        assertThatThrownBy(() -> agentStub.getPolicy(GetPolicyRequest.getDefaultInstance()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.UNAUTHENTICATED));
    }
}
