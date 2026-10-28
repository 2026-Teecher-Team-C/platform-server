package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.teecherteamc.platform.proto.verdict.v1.Decision;
import com.teecherteamc.platform.proto.verdict.v1.DecisionSource;
import com.teecherteamc.platform.proto.verdict.v1.SubmitFileMetadata;
import com.teecherteamc.platform.proto.verdict.v1.SubmitFileRequest;
import com.teecherteamc.platform.proto.verdict.v1.SubmitFileResponse;
import com.teecherteamc.platform.proto.verdict.v1.VerdictServiceGrpc;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.grpc.client.ImportGrpcClients;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "submit-file.max-spool-bytes=100")
@AutoConfigureTestGrpcTransport
@ImportGrpcClients(types = VerdictServiceGrpc.VerdictServiceStub.class)
@Import(TestcontainersConfiguration.class)
class SubmitFileGrpcIntegrationTest {

    @Autowired
    private VerdictServiceGrpc.VerdictServiceStub verdictStub;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 정상_파일은_ALLOW를_반환하고_file_verdicts와_analyses에_기록된다() throws Exception {
        byte[] content = "그냥 평범한 파일 내용".getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256Hex(content);

        SubmitFileResponse response = submitFile(sha256, content);

        assertThat(response.getDecision()).isEqualTo(Decision.DECISION_ALLOW);
        assertThat(response.getSource()).isEqualTo(DecisionSource.DECISION_SOURCE_ENGINE);

        Map<String, Object> verdictRow =
                jdbcTemplate.queryForMap("SELECT * FROM file_verdicts WHERE sha256 = ?", sha256);
        assertThat(verdictRow.get("verdict")).isEqualTo("CLEAN");
        assertThat(verdictRow.get("verdict_source")).isEqualTo("ENGINE");
        // 엔진이 활성 룰셋(v1 시드)을 적재한 뒤 검사하므로 엔진이 보고하는 버전은 1이다.
        assertThat(verdictRow.get("rulesets_version")).isEqualTo(1);

        Map<String, Object> analysisRow =
                jdbcTemplate.queryForMap("SELECT * FROM analyses WHERE sha256 = ?", sha256);
        assertThat(analysisRow.get("status")).isEqualTo("SUCCESS");
        assertThat(analysisRow.get("verdict")).isEqualTo("CLEAN");
    }

    @Test
    void 엔진_호출이_실패해도_UNAVAILABLE로_거부하면서_감사_기록은_남긴다() throws Exception {
        byte[] content = ("크래시 유발" + FakeDetectionEngineService.CRASH_MARKER).getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256Hex(content);

        assertThatThrownBy(() -> submitFile(sha256, content))
                .extracting(this::unwrapStatus)
                .isEqualTo(Status.Code.UNAVAILABLE);

        Map<String, Object> verdictRow =
                jdbcTemplate.queryForMap("SELECT * FROM file_verdicts WHERE sha256 = ?", sha256);
        assertThat(verdictRow.get("verdict")).isEqualTo("ERROR");

        Map<String, Object> analysisRow =
                jdbcTemplate.queryForMap("SELECT * FROM analyses WHERE sha256 = ?", sha256);
        assertThat(analysisRow.get("status")).isEqualTo("TIMEOUT");
        assertThat(analysisRow.get("verdict")).isEqualTo("ERROR");
        assertThat(analysisRow.get("rulesets_version")).isEqualTo(0);
    }

    @Test
    void 같은_새_파일이_동시에_두_번_들어와도_둘_다_에러_없이_응답한다() throws Exception {
        byte[] content = "동시_업로드_레이스_테스트".getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256Hex(content);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> raceSubmit(sha256, content, ready, go));
            var second = pool.submit(() -> raceSubmit(sha256, content, ready, go));
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();

            SubmitFileResponse r1 = first.get(15, TimeUnit.SECONDS);
            SubmitFileResponse r2 = second.get(15, TimeUnit.SECONDS);

            assertThat(r1.getDecision()).isEqualTo(Decision.DECISION_ALLOW);
            assertThat(r2.getDecision()).isEqualTo(Decision.DECISION_ALLOW);
        } finally {
            pool.shutdownNow();
        }

        Integer verdictCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM file_verdicts WHERE sha256 = ?", Integer.class, sha256);
        assertThat(verdictCount).isEqualTo(1);
    }

    @Test
    void 엔진이_실패_상태인데_CLEAN을_보내도_캐시엔_ERROR로_저장된다() throws Exception {
        byte[] content =
                ("모순된 응답 테스트" + FakeDetectionEngineService.INCONSISTENT_MARKER).getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256Hex(content);

        SubmitFileResponse response = submitFile(sha256, content);

        // 에이전트한텐 status(UNSUPPORTED)를 보고 BLOCK으로 응답 — verdict(CLEAN)을 안 믿는다.
        assertThat(response.getDecision()).isEqualTo(Decision.DECISION_BLOCK);

        // 캐시에도 CLEAN이 아니라 ERROR로 저장돼야, 다음 CheckHash가 잘못 ALLOW를 내주지 않는다.
        Map<String, Object> verdictRow =
                jdbcTemplate.queryForMap("SELECT * FROM file_verdicts WHERE sha256 = ?", sha256);
        assertThat(verdictRow.get("verdict")).isEqualTo("ERROR");
    }

    private SubmitFileResponse raceSubmit(String sha256, byte[] content, CountDownLatch ready, CountDownLatch go) {
        try {
            ready.countDown();
            go.await(10, TimeUnit.SECONDS);
            return submitFile(sha256, content);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void 악성_파일은_BLOCK과_매치된_룰을_반환한다() throws Exception {
        byte[] content = ("나쁜 파일" + FakeDetectionEngineService.MALICIOUS_MARKER).getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256Hex(content);

        SubmitFileResponse response = submitFile(sha256, content);

        assertThat(response.getDecision()).isEqualTo(Decision.DECISION_BLOCK);
        assertThat(response.getMatchedRulesList()).containsExactly("fake_rule");

        Map<String, Object> verdictRow =
                jdbcTemplate.queryForMap("SELECT * FROM file_verdicts WHERE sha256 = ?", sha256);
        assertThat(verdictRow.get("verdict")).isEqualTo("MALICIOUS");
    }

    @Test
    void 신규_악성_판정은_S3_업로드_위치를_스테이징한다() throws Exception {
        byte[] content =
                ("격리 대상" + FakeDetectionEngineService.MALICIOUS_MARKER).getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256Hex(content);

        submitFile(sha256, content);

        Map<String, Object> pendingRow =
                jdbcTemplate.queryForMap("SELECT * FROM pending_quarantine_uploads WHERE sha256 = ?", sha256);
        assertThat(pendingRow.get("file_size")).isEqualTo((long) content.length);
        assertThat(pendingRow.get("s3_bucket")).isNotNull();
        assertThat(pendingRow.get("s3_key")).isNotNull();
    }

    @Test
    void 캐시_히트_BLOCK은_S3에_중복_업로드하지_않는다() throws Exception {
        byte[] content =
                ("재제출 격리 파일" + FakeDetectionEngineService.MALICIOUS_MARKER).getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256Hex(content);

        submitFile(sha256, content); // 신규 엔진 호출 — 스테이징 행 1개 생김
        SubmitFileResponse second = submitFile(sha256, content); // 캐시 히트
        assertThat(second.getSource()).isEqualTo(DecisionSource.DECISION_SOURCE_CACHE);

        Integer pendingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pending_quarantine_uploads WHERE sha256 = ?", Integer.class, sha256);
        assertThat(pendingCount).isEqualTo(1);
    }

    @Test
    void 엔진_장애로_인한_BLOCK은_S3에_업로드하지_않는다() throws Exception {
        byte[] content = ("엔진_죽음" + FakeDetectionEngineService.CRASH_MARKER).getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256Hex(content);

        assertThatThrownBy(() -> submitFile(sha256, content))
                .extracting(this::unwrapStatus)
                .isEqualTo(Status.Code.UNAVAILABLE);

        Integer pendingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pending_quarantine_uploads WHERE sha256 = ?", Integer.class, sha256);
        assertThat(pendingCount).isZero();
    }

    @Test
    void 같은_해시를_두번_올리면_두번째는_엔진을_안_거치고_캐시로_응답한다() throws Exception {
        byte[] content = "캐시 재확인 테스트".getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256Hex(content);

        SubmitFileResponse first = submitFile(sha256, content);
        assertThat(first.getSource()).isEqualTo(DecisionSource.DECISION_SOURCE_ENGINE);

        SubmitFileResponse second = submitFile(sha256, content);
        assertThat(second.getDecision()).isEqualTo(Decision.DECISION_ALLOW);
        assertThat(second.getSource()).isEqualTo(DecisionSource.DECISION_SOURCE_CACHE);

        Integer analysisCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM analyses WHERE sha256 = ?", Integer.class, sha256);
        assertThat(analysisCount).isEqualTo(1);
    }

    @Test
    void 주장한_해시와_실제_내용이_다르면_DATA_LOSS로_거부된다() {
        byte[] content = "진짜 내용".getBytes(StandardCharsets.UTF_8);
        String wrongSha256 = "a".repeat(64);

        assertThatThrownBy(() -> submitFile(wrongSha256, content))
                .extracting(this::unwrapStatus)
                .isEqualTo(Status.Code.DATA_LOSS);
    }

    @Test
    void 주장한_file_size와_실제_수신량이_다르면_INVALID_ARGUMENT로_거부된다() throws Exception {
        byte[] content = "열 바이트도 안 되는 내용".getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256Hex(content);

        CompletableFuture<SubmitFileResponse> future = new CompletableFuture<>();
        StreamObserver<SubmitFileRequest> requestObserver = verdictStub.submitFile(futureObserver(future));
        requestObserver.onNext(SubmitFileRequest.newBuilder()
                .setMetadata(SubmitFileMetadata.newBuilder()
                        .setEventId(UUID.randomUUID().toString())
                        .setSha256(sha256)
                        .setFileSize(content.length + 999) // 일부러 실제와 다르게
                        .build())
                .build());
        requestObserver.onNext(
                SubmitFileRequest.newBuilder().setChunk(ByteString.copyFrom(content)).build());
        requestObserver.onCompleted();

        assertThatThrownBy(() -> future.get(10, TimeUnit.SECONDS))
                .extracting(this::unwrapStatus)
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void 스풀_상한을_넘으면_RESOURCE_EXHAUSTED로_거부된다() throws Exception {
        // @SpringBootTest properties로 상한을 100바이트로 낮춰뒀다.
        byte[] content = new byte[1000];
        String sha256 = sha256Hex(content);

        assertThatThrownBy(() -> submitFile(sha256, content))
                .extracting(this::unwrapStatus)
                .isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
    }

    @Test
    void 클라이언트_연결이_중간에_끊기면_스풀_파일이_정리된다() throws Exception {
        long before = countSpoolFiles();

        CompletableFuture<SubmitFileResponse> future = new CompletableFuture<>();
        StreamObserver<SubmitFileRequest> requestObserver = verdictStub.submitFile(futureObserver(future));
        requestObserver.onNext(SubmitFileRequest.newBuilder()
                .setMetadata(SubmitFileMetadata.newBuilder()
                        .setEventId(UUID.randomUUID().toString())
                        .setSha256("b".repeat(64))
                        .setFileSize(100)
                        .build())
                .build());
        requestObserver.onNext(
                SubmitFileRequest.newBuilder().setChunk(ByteString.copyFromUtf8("일부만 보냄")).build());
        // 클라이언트가 끝까지 안 보내고 끊는 상황을 흉내낸다 — onCompleted() 없이 onError()만 호출.
        requestObserver.onError(new RuntimeException("클라이언트가 중간에 포기함"));

        // 서버가 정리를 끝낼 시간을 잠깐 준다.
        Thread.sleep(500);

        assertThat(countSpoolFiles()).isEqualTo(before);
    }

    private long countSpoolFiles() throws Exception {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        try (Stream<Path> files = Files.list(tmp)) {
            return files.filter(p -> p.getFileName().toString().startsWith("submit-file-")).count();
        }
    }

    @Test
    void metadata_없이_청크부터_오면_INVALID_ARGUMENT로_거부된다() {
        CompletableFuture<SubmitFileResponse> future = new CompletableFuture<>();
        StreamObserver<SubmitFileRequest> requestObserver = verdictStub.submitFile(futureObserver(future));
        requestObserver.onNext(
                SubmitFileRequest.newBuilder().setChunk(ByteString.copyFromUtf8("x")).build());
        requestObserver.onCompleted();

        assertThatThrownBy(() -> future.get(10, TimeUnit.SECONDS))
                .extracting(this::unwrapStatus)
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    private SubmitFileResponse submitFile(String sha256, byte[] content) throws Exception {
        CompletableFuture<SubmitFileResponse> future = new CompletableFuture<>();
        StreamObserver<SubmitFileRequest> requestObserver = verdictStub.submitFile(futureObserver(future));

        requestObserver.onNext(SubmitFileRequest.newBuilder()
                .setMetadata(SubmitFileMetadata.newBuilder()
                        .setEventId(UUID.randomUUID().toString())
                        .setSha256(sha256)
                        .setFileSize(content.length)
                        .build())
                .build());

        int chunkSize = 8192;
        for (int offset = 0; offset < content.length; offset += chunkSize) {
            int end = Math.min(offset + chunkSize, content.length);
            requestObserver.onNext(SubmitFileRequest.newBuilder()
                    .setChunk(ByteString.copyFrom(content, offset, end - offset))
                    .build());
        }
        requestObserver.onCompleted();
        return future.get(10, TimeUnit.SECONDS);
    }

    private StreamObserver<SubmitFileResponse> futureObserver(CompletableFuture<SubmitFileResponse> future) {
        return new StreamObserver<>() {
            @Override
            public void onNext(SubmitFileResponse value) {
                future.complete(value);
            }

            @Override
            public void onError(Throwable t) {
                future.completeExceptionally(t);
            }

            @Override
            public void onCompleted() {
                // 응답은 onNext로 이미 받는다.
            }
        };
    }

    private Status.Code unwrapStatus(Throwable thrown) {
        Throwable cause = thrown instanceof ExecutionException ? thrown.getCause() : thrown;
        return ((StatusRuntimeException) cause).getStatus().getCode();
    }

    private static String sha256Hex(byte[] content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(content));
    }
}
