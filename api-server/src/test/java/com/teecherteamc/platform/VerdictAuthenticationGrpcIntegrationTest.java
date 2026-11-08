package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.teecherteamc.platform.proto.verdict.v1.*;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.grpc.client.ImportGrpcClients;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@AutoConfigureTestGrpcTransport
@ImportGrpcClients(types = VerdictServiceGrpc.VerdictServiceBlockingStub.class)
@Import(TestcontainersConfiguration.class)
class VerdictAuthenticationGrpcIntegrationTest {
    @Autowired VerdictServiceGrpc.VerdictServiceBlockingStub stub;
    @Autowired JdbcTemplate jdbc;

    @Test void missing_authentication_is_rejected() { rejected(stub); }
    @Test void invalid_authentication_is_rejected() { rejected(VerdictTestClient.withToken(stub, "invalid-test-token")); }
    @Test void revoked_authentication_is_rejected() {
        var identity = VerdictTestClient.seed(jdbc);
        jdbc.update("UPDATE agents SET status='REVOKED' WHERE agent_id=?", identity.id());
        rejected(VerdictTestClient.withToken(stub, identity.token()));
    }
    @Test void expired_authentication_is_rejected() {
        var identity = VerdictTestClient.seed(jdbc);
        jdbc.update("UPDATE agents SET agent_token_expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE agent_id=?", identity.id());
        rejected(VerdictTestClient.withToken(stub, identity.token()));
    }
    private void rejected(VerdictServiceGrpc.VerdictServiceBlockingStub client) {
        assertThatThrownBy(() -> client.reportEvent(ReportEventRequest.getDefaultInstance()))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> org.assertj.core.api.Assertions.assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
        var result = new java.util.concurrent.CompletableFuture<Throwable>();
        var upload = VerdictServiceGrpc.newStub(client.getChannel()).submitFile(new io.grpc.stub.StreamObserver<SubmitFileResponse>() {
            public void onNext(SubmitFileResponse response) { result.complete(new AssertionError("unauthenticated upload accepted")); }
            public void onError(Throwable error) { result.complete(error); }
            public void onCompleted() { }
        });
        upload.onNext(SubmitFileRequest.getDefaultInstance());
        upload.onCompleted();
        try {
            org.assertj.core.api.Assertions.assertThat(Status.fromThrowable(result.get(10, java.util.concurrent.TimeUnit.SECONDS)).getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
        } catch (Exception e) { throw new AssertionError(e); }
        assertThatThrownBy(() -> client.checkHash(CheckHashRequest.newBuilder().setSha256("a".repeat(64)).build()))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> org.assertj.core.api.Assertions.assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
    }
}
