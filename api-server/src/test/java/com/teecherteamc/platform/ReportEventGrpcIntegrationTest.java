package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Timestamp;
import com.teecherteamc.platform.proto.verdict.v1.DecisionSource;
import com.teecherteamc.platform.proto.verdict.v1.FinalDecision;
import com.teecherteamc.platform.proto.verdict.v1.ReportEventRequest;
import com.teecherteamc.platform.proto.verdict.v1.VerdictServiceGrpc;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Instant;
import java.util.Map;
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
@ImportGrpcClients(types = VerdictServiceGrpc.VerdictServiceBlockingStub.class)
@Import(TestcontainersConfiguration.class)
class ReportEventGrpcIntegrationTest {

    // event/application/PlaceholderAgent.DEV_AGENT_ID 와 동일한 값 — 실제 인증이 생기기 전까지
    // interfaces 레이어가 이 값을 agent_id로 채워 넣으므로, fixture도 같은 값을 써야 FK가 만족된다.
    private static final UUID DEV_AGENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    private VerdictServiceGrpc.VerdictServiceBlockingStub verdictStub;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedDeviceAndAgent() {
        UUID deviceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO devices (device_id, hostname, os_platform) VALUES (?, 'test-host', 'WINDOWS')",
                deviceId);
        jdbcTemplate.update(
                """
                INSERT INTO agents (agent_id, device_id, agent_version, enrollment_token_hash)
                VALUES (?, ?, '0.0.1', 'test-hash')
                ON CONFLICT (agent_id) DO NOTHING
                """,
                DEV_AGENT_ID, deviceId);
    }

    @Test
    void 정상_케이스는_download_events에_기록된다() {
        String sha256 = "a".repeat(64);
        jdbcTemplate.update(
                """
                INSERT INTO file_verdicts (sha256, verdict, verdict_source)
                VALUES (?, 'CLEAN', 'MANUAL')
                """,
                sha256);

        UUID eventId = UUID.randomUUID();
        Instant heldAt = Instant.parse("2026-09-23T00:00:00Z");
        Instant decidedAt = heldAt.plusMillis(1500);

        verdictStub.reportEvent(ReportEventRequest.newBuilder()
                .setEventId(eventId.toString())
                .setSha256(sha256)
                .setRequestHost("example.com")
                .setUrl("https://example.com/file.exe")
                .setFilename("file.exe")
                .setMimeType("application/octet-stream")
                .setContentDisposition("attachment; filename=\"file.exe\"")
                .setFileSize(1024)
                .setDecision(FinalDecision.FINAL_DECISION_RELEASED)
                .setDecisionSource(DecisionSource.DECISION_SOURCE_CACHE)
                .setCacheHit(true)
                .setBytesUploaded(2048)
                .setHeldAt(toTimestamp(heldAt))
                .setDecidedAt(toTimestamp(decidedAt))
                .setHoldDurationMs(1500)
                .build());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM download_events WHERE event_id = ?", eventId);
        assertThat(row.get("sha256")).isEqualTo(sha256);
        assertThat(row.get("agent_id")).isEqualTo(DEV_AGENT_ID);
        assertThat(row.get("pipeline_status")).isEqualTo("COMPLETED");
        assertThat(row.get("decision")).isEqualTo("RELEASED");
        assertThat(row.get("decision_source")).isEqualTo("CACHE");
        assertThat(row.get("hold_duration_ms")).isEqualTo(1500);
        assertThat(row.get("request_host")).isEqualTo("example.com");
        assertThat(row.get("url")).isEqualTo("https://example.com/file.exe");
        assertThat(row.get("filename")).isEqualTo("file.exe");
        assertThat(row.get("mime_type")).isEqualTo("application/octet-stream");
        assertThat(row.get("content_disposition")).isEqualTo("attachment; filename=\"file.exe\"");
        assertThat(row.get("file_size")).isEqualTo(1024L);
        assertThat(row.get("cache_hit")).isEqualTo(true);
        assertThat(row.get("bytes_uploaded")).isEqualTo(2048L);
        assertThat(((java.sql.Timestamp) row.get("held_at")).toInstant()).isEqualTo(heldAt);
        assertThat(((java.sql.Timestamp) row.get("decided_at")).toInstant()).isEqualTo(decidedAt);
    }

    @Test
    void file_verdicts에_없는_sha256은_NULL로_저장된다() {
        String unknownSha256 = "b".repeat(64);
        UUID eventId = UUID.randomUUID();
        Instant heldAt = Instant.now();

        verdictStub.reportEvent(ReportEventRequest.newBuilder()
                .setEventId(eventId.toString())
                .setSha256(unknownSha256)
                .setRequestHost("example.com")
                .setUrl("https://example.com/file.exe")
                .setDecision(FinalDecision.FINAL_DECISION_BLOCKED)
                .setDecisionSource(DecisionSource.DECISION_SOURCE_ENGINE)
                .setHeldAt(toTimestamp(heldAt))
                .setDecidedAt(toTimestamp(heldAt.plusMillis(200)))
                .build());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM download_events WHERE event_id = ?", eventId);
        assertThat(row.get("sha256")).isNull();
    }

    @Test
    void 빈_sha256_바이패스_케이스는_NULL로_저장된다() {
        UUID eventId = UUID.randomUUID();
        Instant heldAt = Instant.now();

        verdictStub.reportEvent(ReportEventRequest.newBuilder()
                .setEventId(eventId.toString())
                .setRequestHost("update.microsoft.com")
                .setUrl("https://update.microsoft.com/patch.msi")
                .setDecision(FinalDecision.FINAL_DECISION_BYPASSED)
                .setDecisionSource(DecisionSource.DECISION_SOURCE_POLICY)
                .setHeldAt(toTimestamp(heldAt))
                .build());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM download_events WHERE event_id = ?", eventId);
        assertThat(row.get("sha256")).isNull();
        assertThat(row.get("decision")).isEqualTo("BYPASSED");
    }

    @Test
    void decision이_UNSPECIFIED면_INVALID_ARGUMENT로_거부하고_행을_안_만든다() {
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> verdictStub.reportEvent(ReportEventRequest.newBuilder()
                        .setEventId(eventId.toString())
                        .setRequestHost("example.com")
                        .setUrl("https://example.com/file.exe")
                        .setHeldAt(toTimestamp(Instant.now()))
                        .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM download_events WHERE event_id = ?", Integer.class, eventId);
        assertThat(count).isZero();
    }

    @Test
    void 중복된_event_id는_ALREADY_EXISTS로_거부된다() {
        UUID eventId = UUID.randomUUID();
        Instant heldAt = Instant.now();
        ReportEventRequest request = ReportEventRequest.newBuilder()
                .setEventId(eventId.toString())
                .setRequestHost("example.com")
                .setUrl("https://example.com/file.exe")
                .setDecision(FinalDecision.FINAL_DECISION_RELEASED)
                .setHeldAt(toTimestamp(heldAt))
                .build();

        verdictStub.reportEvent(request);

        assertThatThrownBy(() -> verdictStub.reportEvent(request))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.ALREADY_EXISTS));
    }

    @Test
    void event_id가_UUID_형식이_아니면_INVALID_ARGUMENT로_거부된다() {
        assertThatThrownBy(() -> verdictStub.reportEvent(ReportEventRequest.newBuilder()
                        .setEventId("not-a-uuid")
                        .setRequestHost("example.com")
                        .setUrl("https://example.com/file.exe")
                        .setDecision(FinalDecision.FINAL_DECISION_RELEASED)
                        .setHeldAt(toTimestamp(Instant.now()))
                        .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    @Test
    void filename이_512자를_넘으면_INVALID_ARGUMENT로_거부된다() {
        assertThatThrownBy(() -> verdictStub.reportEvent(ReportEventRequest.newBuilder()
                        .setEventId(UUID.randomUUID().toString())
                        .setRequestHost("example.com")
                        .setUrl("https://example.com/file.exe")
                        .setFilename("a".repeat(513))
                        .setDecision(FinalDecision.FINAL_DECISION_RELEASED)
                        .setHeldAt(toTimestamp(Instant.now()))
                        .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    @Test
    void held_at의_nanos가_범위를_벗어나면_INVALID_ARGUMENT로_거부된다() {
        Timestamp invalidNanos = Timestamp.newBuilder()
                .setSeconds(Instant.now().getEpochSecond())
                .setNanos(1_000_000_000)
                .build();

        assertThatThrownBy(() -> verdictStub.reportEvent(ReportEventRequest.newBuilder()
                        .setEventId(UUID.randomUUID().toString())
                        .setRequestHost("example.com")
                        .setUrl("https://example.com/file.exe")
                        .setDecision(FinalDecision.FINAL_DECISION_RELEASED)
                        .setHeldAt(invalidNanos)
                        .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    @Test
    void decided_at의_seconds가_범위를_벗어나면_INVALID_ARGUMENT로_거부된다() {
        Timestamp outOfRangeSeconds = Timestamp.newBuilder()
                .setSeconds(Long.MAX_VALUE)
                .build();

        assertThatThrownBy(() -> verdictStub.reportEvent(ReportEventRequest.newBuilder()
                        .setEventId(UUID.randomUUID().toString())
                        .setRequestHost("example.com")
                        .setUrl("https://example.com/file.exe")
                        .setDecision(FinalDecision.FINAL_DECISION_RELEASED)
                        .setHeldAt(toTimestamp(Instant.now()))
                        .setDecidedAt(outOfRangeSeconds)
                        .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    private static Timestamp toTimestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}
