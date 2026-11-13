package com.teecherteamc.platform;

import com.teecherteamc.platform.fleet.domain.TokenHash;
import io.grpc.Metadata;
import io.grpc.stub.AbstractStub;
import io.grpc.stub.MetadataUtils;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** 실제 agent 토큰을 DB에 등록하고 클라이언트에만 인증 헤더를 붙인다. 서버 인증은 우회하지 않는다. */
final class VerdictTestClient {
    record Identity(UUID id, String token) {}

    static Identity seed(JdbcTemplate jdbc) {
        UUID device = UUID.randomUUID(), agent = UUID.randomUUID();
        String token = "verdict-test-" + UUID.randomUUID();
        jdbc.update("INSERT INTO devices(device_id, hostname, os_platform) VALUES (?, 'verdict-test', 'LINUX')", device);
        jdbc.update("""
                INSERT INTO agents(agent_id, device_id, agent_version, enrollment_token_hash, status,
                    agent_token_hash, agent_token_expires_at) VALUES (?, ?, 'test', 'test', 'ACTIVE', ?, ?)
                """, agent, device, TokenHash.sha256Hex(token), Timestamp.from(Instant.now().plusSeconds(3600)));
        return new Identity(agent, token);
    }

    static <S extends AbstractStub<S>> S withToken(S stub, String token) {
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    static <S extends AbstractStub<S>> S authenticated(S stub, JdbcTemplate jdbc) {
        return withToken(stub, seed(jdbc).token());
    }
}
