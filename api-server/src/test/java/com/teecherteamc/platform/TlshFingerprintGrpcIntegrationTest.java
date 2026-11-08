package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import com.teecherteamc.platform.proto.verdict.v1.*;
import io.grpc.stub.StreamObserver;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.grpc.client.ImportGrpcClients;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@AutoConfigureTestGrpcTransport
@ImportGrpcClients(types = VerdictServiceGrpc.VerdictServiceStub.class)
@Import(TestcontainersConfiguration.class)
class TlshFingerprintGrpcIntegrationTest {
    @org.junit.jupiter.api.BeforeEach
    void authenticateVerdictClient() {
        stub = VerdictTestClient.authenticated(stub, jdbc);
    }
    @Autowired VerdictServiceGrpc.VerdictServiceStub stub;
    @Autowired JdbcTemplate jdbc;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.teecherteamc.platform.verdict.infrastructure.OfficialTlshCalculator calculator;

    @Test
    void verified_upload_persists_reference_fingerprint_without_changing_decision() throws Exception {
        byte[] bytes = getClass().getResourceAsStream("/tlsh/reference.txt").readAllBytes();
        String sha = sha(bytes);
        assertThat(upload(sha, bytes).getDecision()).isEqualTo(Decision.DECISION_ALLOW);
        var row = jdbc.queryForMap("SELECT * FROM file_fingerprints WHERE sha256 = ?", sha);
        assertThat(row.get("calculation_status")).isEqualTo("CALCULATED");
        assertThat(row.get("tlsh")).isEqualTo("T1A7D14D74F9407AE28FAE92B7CCFE6BA1509763248ED678CC9474B7C71593294FD00851");
        assertThat(row.get("provenance")).isEqualTo("VERIFIED_UPLOAD");
    }

    @Test
    void cache_hit_upload_backfills_a_missing_fingerprint_without_another_analysis() throws Exception {
        byte[] bytes = "cache-backfill".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha = sha(bytes);
        upload(sha, bytes);
        jdbc.update("DELETE FROM file_fingerprints WHERE sha256 = ?", sha);
        var response = upload(sha, bytes);
        assertThat(response.getSource()).isEqualTo(DecisionSource.DECISION_SOURCE_CACHE);
        assertThat(response.getDecision()).isEqualTo(Decision.DECISION_ALLOW);
        assertThat(jdbc.queryForObject("SELECT calculation_status FROM file_fingerprints WHERE sha256 = ?",
                String.class, sha)).isEqualTo("TOO_SHORT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM analyses WHERE sha256 = ?", Integer.class, sha))
                .isEqualTo(1);
    }

    @Test
    void mismatch_leaves_no_fingerprint_under_claimed_or_actual_sha() throws Exception {
        byte[] bytes = "untrusted-upload-hash-mismatch".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String claimed = "c".repeat(64);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> upload(claimed, bytes))
                .hasCauseInstanceOf(io.grpc.StatusRuntimeException.class)
                .satisfies(error -> assertThat(((io.grpc.StatusRuntimeException) error.getCause()).getStatus().getCode())
                        .isEqualTo(io.grpc.Status.Code.DATA_LOSS));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_fingerprints WHERE sha256 IN (?, ?)",
                Integer.class, claimed, sha(bytes))).isZero();
    }

    @Test
    void tiny_and_low_entropy_uploads_keep_clean_decisions_and_nullable_fingerprints() throws Exception {
        for (byte[] bytes : new byte[][]{new byte[49], new byte[4096]}) {
            String sha = sha(bytes);
            assertThat(upload(sha, bytes).getDecision()).isEqualTo(Decision.DECISION_ALLOW);
            var row = jdbc.queryForMap("SELECT * FROM file_fingerprints WHERE sha256 = ?", sha);
            assertThat(row.get("tlsh")).isNull();
            assertThat(row.get("calculation_status")).isEqualTo(bytes.length < 50 ? "TOO_SHORT" : "LOW_ENTROPY");
        }
    }

    @Test
    void real_padding_variant_has_different_sha_and_measured_reference_distance() throws Exception {
        byte[] original = getClass().getResourceAsStream("/tlsh/reference.txt").readAllBytes();
        byte[] padded = java.util.Arrays.copyOf(original, original.length + 64);
        java.util.Arrays.fill(padded, original.length, padded.length, (byte) ' ');
        assertThat(sha(original)).isNotEqualTo(sha(padded));
        assertThat(upload(sha(original), original).getDecision()).isEqualTo(Decision.DECISION_ALLOW);
        assertThat(upload(sha(padded), padded).getDecision()).isEqualTo(Decision.DECISION_ALLOW);
        String first = jdbc.queryForObject("SELECT tlsh FROM file_fingerprints WHERE sha256 = ?",
                String.class, sha(original));
        String second = jdbc.queryForObject("SELECT tlsh FROM file_fingerprints WHERE sha256 = ?",
                String.class, sha(padded));
        int distance = com.trendmicro.tlsh.Tlsh.fromTlshStr(first)
                .totalDiff(com.trendmicro.tlsh.Tlsh.fromTlshStr(second), true);
        assertThat(distance).isEqualTo(17); // 고정 revision의 공식 C++ 구현에서 측정한 길이 포함 거리다.
    }

    @Test
    void unavailable_calculation_does_not_bypass_yara_block_or_engine_fail_close() throws Exception {
        org.mockito.Mockito.doReturn(new com.teecherteamc.platform.verdict.domain.FingerprintCalculation(null, "UNAVAILABLE"))
                .when(calculator).calculate(org.mockito.ArgumentMatchers.any(java.nio.file.Path.class));
        byte[] malicious = ("unavailable-tlsh-" + FakeDetectionEngineService.MALICIOUS_MARKER)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var response = upload(sha(malicious), malicious);
        assertThat(response.getDecision()).isEqualTo(Decision.DECISION_BLOCK);
        assertThat(response.getSource()).isEqualTo(DecisionSource.DECISION_SOURCE_ENGINE);
        assertThat(jdbc.queryForObject("SELECT calculation_status FROM file_fingerprints WHERE sha256 = ?",
                String.class, sha(malicious))).isEqualTo("UNAVAILABLE");
        byte[] crashed = ("unavailable-tlsh-" + FakeDetectionEngineService.CRASH_MARKER)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> upload(sha(crashed), crashed))
                .satisfies(error -> assertThat(((io.grpc.StatusRuntimeException) error.getCause()).getStatus().getCode())
                        .isEqualTo(io.grpc.Status.Code.UNAVAILABLE));
        assertThat(jdbc.queryForObject("SELECT verdict FROM file_verdicts WHERE sha256 = ?",
                String.class, sha(crashed))).isEqualTo("ERROR");
    }

    @Test
    void fingerprint_database_failure_is_not_treated_as_calculation_unavailable() throws Exception {
        byte[] bytes = "fingerprint-database-failure".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        jdbc.execute("ALTER TABLE file_fingerprints RENAME TO temporarily_unavailable_fingerprints");
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> upload(sha(bytes), bytes))
                    .satisfies(error -> assertThat(((io.grpc.StatusRuntimeException) error.getCause()).getStatus().getCode())
                            .isEqualTo(io.grpc.Status.Code.INTERNAL));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM file_verdicts WHERE sha256 = ?",
                    Integer.class, sha(bytes))).isZero();
        } finally {
            jdbc.execute("ALTER TABLE temporarily_unavailable_fingerprints RENAME TO file_fingerprints");
        }
    }

    SubmitFileResponse upload(String sha, byte[] bytes) throws Exception {
        var future = new CompletableFuture<SubmitFileResponse>();
        var observer = stub.submitFile(new StreamObserver<SubmitFileResponse>() {
            public void onNext(SubmitFileResponse value) { future.complete(value); }
            public void onError(Throwable error) { future.completeExceptionally(error); }
            public void onCompleted() { }
        });
        observer.onNext(SubmitFileRequest.newBuilder().setMetadata(SubmitFileMetadata.newBuilder()
                .setEventId(UUID.randomUUID().toString()).setSha256(sha).setFileSize(bytes.length)).build());
        for (int i = 0; i < bytes.length; i += 127) {
            observer.onNext(SubmitFileRequest.newBuilder()
                    .setChunk(ByteString.copyFrom(bytes, i, Math.min(127, bytes.length - i))).build());
        }
        observer.onCompleted();
        return future.get(15, TimeUnit.SECONDS);
    }

    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
