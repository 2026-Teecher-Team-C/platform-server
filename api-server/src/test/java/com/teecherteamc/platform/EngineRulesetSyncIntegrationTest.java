package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.teecherteamc.platform.proto.verdict.v1.Decision;
import com.teecherteamc.platform.proto.verdict.v1.SubmitFileMetadata;
import com.teecherteamc.platform.proto.verdict.v1.SubmitFileRequest;
import com.teecherteamc.platform.proto.verdict.v1.SubmitFileResponse;
import com.teecherteamc.platform.proto.verdict.v1.VerdictServiceGrpc;
import com.teecherteamc.platform.ruleset.application.ActiveRulesetService;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
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
@ImportGrpcClients(types = VerdictServiceGrpc.VerdictServiceStub.class)
@Import(TestcontainersConfiguration.class)
class EngineRulesetSyncIntegrationTest {

    @Autowired
    private VerdictServiceGrpc.VerdictServiceStub verdictStub;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ActiveRulesetService activeRulesetService;

    @BeforeEach
    void setUp() {
        RulesetTestSupport.restoreSeed(jdbcTemplate);
        FakeDetectionEngineService.reset();
    }

    @Test
    void 엔진이_다른_룰셋을_들고_있으면_적재하고_같은_파일을_다시_검사한다() throws Exception {
        byte[] content = ("룰셋 동기화" + FakeDetectionEngineService.MALICIOUS_MARKER + UUID.randomUUID())
                .getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256Hex(content);

        SubmitFileResponse response = submitFile(sha256, content);

        assertThat(response.getDecision()).isEqualTo(Decision.DECISION_BLOCK);
        assertThat(FakeDetectionEngineService.loadCount()).isEqualTo(1);
        assertThat(FakeDetectionEngineService.loadedKey()).isEqualTo(activeRulesetService.activeSnapshot().key());
        Integer analysisVersion = jdbcTemplate.queryForObject(
                "SELECT rulesets_version FROM analyses WHERE sha256 = ?", Integer.class, sha256);
        assertThat(analysisVersion).isEqualTo(activeRulesetService.activeSnapshot().version());
        String verdict = jdbcTemplate.queryForObject(
                "SELECT verdict FROM file_verdicts WHERE sha256 = ?", String.class, sha256);
        assertThat(verdict).isEqualTo("MALICIOUS");
    }

    @Test
    void 키가_맞으면_적재하지_않는다() throws Exception {
        submitFile(sha256Hex("첫 파일".getBytes(StandardCharsets.UTF_8)), "첫 파일".getBytes(StandardCharsets.UTF_8));
        assertThat(FakeDetectionEngineService.loadCount()).isEqualTo(1);

        byte[] other = ("다른 내용" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        SubmitFileResponse response = submitFile(sha256Hex(other), other);

        assertThat(response.getDecision()).isEqualTo(Decision.DECISION_ALLOW);
        assertThat(FakeDetectionEngineService.loadCount()).isEqualTo(1);
    }

    @Test
    void 엔진이_UNAVAILABLE이면_재전송하지_않는다() throws Exception {
        activeRulesetService.pushActive(); // 키를 미리 맞춰 둔다 — 이후 loadCount가 늘면 재전송이 일어난 것
        int loadsBefore = FakeDetectionEngineService.loadCount();
        byte[] content = ("크래시" + FakeDetectionEngineService.CRASH_MARKER).getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256Hex(content);

        assertThatThrownBy(() -> submitFile(sha256, content))
                .extracting(this::unwrapStatus)
                .isEqualTo(Status.Code.UNAVAILABLE);

        assertThat(FakeDetectionEngineService.loadCount()).isEqualTo(loadsBefore);
        String verdict = jdbcTemplate.queryForObject(
                "SELECT verdict FROM file_verdicts WHERE sha256 = ?", String.class, sha256);
        assertThat(verdict).isEqualTo("ERROR");
    }

    @Test
    void 엔진이_판정에_쓴_키가_활성_키와_같으면_stale이_아니다() throws Exception {
        byte[] content = ("같은 키" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256Hex(content);

        submitFile(sha256, content);

        assertThat(isStale(sha256)).isFalse();
    }

    @Test
    void 엔진이_보고한_키가_저장_시점의_활성_키와_다르면_판정을_stale로_저장한다() throws Exception {
        activeRulesetService.pushActive();
        FakeDetectionEngineService.reportKey("1:0000000000000000");
        byte[] content = ("옛 키 보고" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256Hex(content);

        SubmitFileResponse response = submitFile(sha256, content);

        assertThat(response.getDecision()).isEqualTo(Decision.DECISION_ALLOW);
        assertThat(isStale(sha256)).isTrue();
        // stale은 캐시 미스다 — 같은 파일이 다시 오면 엔진을 다시 부른다.
        int analyzesBefore = FakeDetectionEngineService.analyzeCount();
        submitFile(sha256, content);
        assertThat(FakeDetectionEngineService.analyzeCount()).isEqualTo(analyzesBefore + 1);
    }

    @Test
    void 분석_도중_룰_구성이_바뀌어_커밋되면_옛_룰로_낸_판정은_stale로_저장된다() throws Exception {
        activeRulesetService.pushActive();
        // 엔진이 옛 룰로 판정하는 동안 다른 관리자가 활성 버전의 룰을 끈 것을 흉내낸다. 이벤트·stale UPDATE는
        // 이 행이 아직 없을 때 이미 지나갔다고 보고, 저장 시점의 키 비교만으로 잡히는지 본다.
        FakeDetectionEngineService.beforeNextAnalyzeVerdict(() -> jdbcTemplate.update(
                "UPDATE yara_rules SET enabled = false WHERE rulesets_version = 1 AND rule_name = 'EICAR_Test_File'"));
        byte[] content = ("경합" + FakeDetectionEngineService.MALICIOUS_MARKER + UUID.randomUUID())
                .getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256Hex(content);

        SubmitFileResponse response = submitFile(sha256, content);

        assertThat(response.getDecision()).isEqualTo(Decision.DECISION_BLOCK);
        assertThat(isStale(sha256)).isTrue();
    }

    private boolean isStale(String sha256) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT is_stale FROM file_verdicts WHERE sha256 = ?", Boolean.class, sha256));
    }

    private String verdictOf(String sha256) {
        return jdbcTemplate.queryForObject("SELECT verdict FROM file_verdicts WHERE sha256 = ?", String.class, sha256);
    }

    private SubmitFileResponse submitFile(String sha256, byte[] content) throws Exception {
        CompletableFuture<SubmitFileResponse> future = new CompletableFuture<>();
        StreamObserver<SubmitFileRequest> requestObserver = verdictStub.submitFile(new StreamObserver<>() {
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
        });
        requestObserver.onNext(SubmitFileRequest.newBuilder()
                .setMetadata(SubmitFileMetadata.newBuilder()
                        .setEventId(UUID.randomUUID().toString())
                        .setSha256(sha256)
                        .setFileSize(content.length)
                        .build())
                .build());
        requestObserver.onNext(SubmitFileRequest.newBuilder().setChunk(ByteString.copyFrom(content)).build());
        requestObserver.onCompleted();
        return future.get(10, TimeUnit.SECONDS);
    }

    private Status.Code unwrapStatus(Throwable thrown) {
        Throwable cause = thrown instanceof ExecutionException ? thrown.getCause() : thrown;
        return ((StatusRuntimeException) cause).getStatus().getCode();
    }

    private static String sha256Hex(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
