package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.teecherteamc.platform.event.application.DownloadEventStreamMessage;
import com.teecherteamc.platform.event.application.EventStreamPublisher;
import com.teecherteamc.platform.event.application.ReportEventCommand;
import com.teecherteamc.platform.event.application.ReportEventUseCase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

// SSE는 응답이 끝나지 않아 TestRestTemplate으로 읽을 수 없다. 실제 포트로 java.net.http.HttpClient를 붙여 줄 단위로 읽는다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfiguration.class, AdminApiClient.class})
class EventStreamIntegrationTest {

    private static final String STREAM_PATH = "/api/admin/events/stream";

    @LocalServerPort
    private int port;

    @Autowired
    private AdminApiClient adminClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReportEventUseCase reportEventUseCase;

    @Autowired
    private TransactionTemplate transactionTemplate;

    // 진짜 객체를 그대로 쓰되 publish 호출 여부만 확인한다.
    @MockitoSpyBean
    private EventStreamPublisher eventStreamPublisher;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void 로그인_없이_연결하면_401이다() throws Exception {
        HttpResponse<Stream<String>> response = connect(null);
        try {
            assertThat(response.statusCode()).isEqualTo(401);
        } finally {
            response.body().close();
        }
    }

    @Test
    void 로그인하면_연결되고_connected_주석이_먼저_온다() throws Exception {
        HttpResponse<Stream<String>> response = connect(adminClient.accessToken());
        try {
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
            assertThat(readLines(response.body(), 1)).containsExactly(":connected");
        } finally {
            response.body().close();
        }
    }

    @Test
    void 차단_이벤트가_저장되면_구독자가_download_event로_받고_허용은_안_온다() throws Exception {
        UUID agentId = seedAgent("DESKTOP-SSE");
        HttpResponse<Stream<String>> response = connect(adminClient.accessToken());
        try {
            Iterator<String> lines = response.body().iterator();
            assertThat(lines.next()).isEqualTo(":connected");

            UUID releasedId = UUID.randomUUID();
            UUID blockedId = UUID.randomUUID();
            reportEventUseCase.reportEvent(command(releasedId, agentId, "RELEASED"));
            reportEventUseCase.reportEvent(command(blockedId, agentId, "BLOCKED"));

            // 허용(RELEASED)이 왔다면 이 첫 이벤트가 releasedId였을 것이다.
            List<String> event = CompletableFuture.supplyAsync(() -> readUntilData(lines))
                    .get(10, TimeUnit.SECONDS);

            assertThat(event).contains("event:download-event");
            String data = event.get(event.size() - 1);
            assertThat(data).startsWith("data:");
            assertThat(data).contains(blockedId.toString());
            assertThat(data).contains("\"hostname\":\"DESKTOP-SSE\"");
            assertThat(data).contains("\"decision\":\"BLOCKED\"");
            assertThat(data).doesNotContain(releasedId.toString());
        } finally {
            response.body().close();
        }
    }

    @Test
    void 롤백된_트랜잭션의_차단_이벤트는_알림이_나가지_않는다() {
        UUID agentId = seedAgent("DESKTOP-ROLLBACK");
        UUID eventId = UUID.randomUUID();

        transactionTemplate.executeWithoutResult(status -> {
            reportEventUseCase.reportEvent(command(eventId, agentId, "BLOCKED"));
            status.setRollbackOnly();
        });

        verify(eventStreamPublisher, never()).publish(
                eq("download-event"),
                argThat(data -> data instanceof DownloadEventStreamMessage message
                        && message.eventId().equals(eventId)));
        Integer saved = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM download_events WHERE event_id = ?", Integer.class, eventId);
        assertThat(saved).isZero();
    }

    private HttpResponse<Stream<String>> connect(String accessToken) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + STREAM_PATH))
                .header("Accept", "text/event-stream");
        if (accessToken != null) {
            request.header("Authorization", "Bearer " + accessToken);
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofLines());
    }

    private List<String> readLines(Stream<String> body, int count) throws Exception {
        Iterator<String> lines = body.iterator();
        return CompletableFuture.supplyAsync(() -> {
            List<String> read = new ArrayList<>();
            while (read.size() < count && lines.hasNext()) {
                read.add(lines.next());
            }
            return read;
        }).get(10, TimeUnit.SECONDS);
    }

    private static List<String> readUntilData(Iterator<String> lines) {
        List<String> read = new ArrayList<>();
        while (lines.hasNext()) {
            String line = lines.next();
            read.add(line);
            if (line.startsWith("data:")) {
                break;
            }
        }
        return read;
    }

    private UUID seedAgent(String hostname) {
        UUID deviceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO devices (device_id, hostname, os_platform) VALUES (?, ?, 'WINDOWS')",
                deviceId, hostname);
        UUID agentId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO agents (agent_id, device_id, agent_version, enrollment_token_hash)
                VALUES (?, ?, '0.0.1', ?)
                """,
                agentId, deviceId, "hash-" + agentId);
        return agentId;
    }

    private static ReportEventCommand command(UUID eventId, UUID agentId, String decision) {
        Instant now = Instant.now();
        return new ReportEventCommand(
                eventId, agentId, null, "malicious.example.com", "https://malicious.example.com/f.exe", "f.exe",
                "application/octet-stream", null, 1024L, decision, "ENGINE", false, 0L, now, now, 100);
    }
}
