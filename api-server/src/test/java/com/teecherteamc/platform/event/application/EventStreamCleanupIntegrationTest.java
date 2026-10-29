package com.teecherteamc.platform.event.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.teecherteamc.platform.AdminApiClient;
import com.teecherteamc.platform.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

// subscriberCount()/heartbeat()가 패키지 전용이라 같은 패키지에 둔다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfiguration.class, AdminApiClient.class})
class EventStreamCleanupIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private AdminApiClient adminClient;

    @Autowired
    private EventStreamPublisher publisher;

    @Test
    void 클라이언트가_연결을_끊으면_구독자_목록에서_빠진다() throws Exception {
        int before = publisher.subscriberCount();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/admin/events/stream"))
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + adminClient.accessToken())
                .build();
        HttpResponse<Stream<String>> response =
                HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofLines());
        assertThat(response.body().iterator().next()).isEqualTo(":connected");
        assertThat(publisher.subscriberCount()).isEqualTo(before + 1);

        response.body().close();

        // 끊긴 소켓에는 첫 write가 성공할 수 있어서 heartbeat를 몇 번 보내야 IOException이 나서 목록에서 빠진다.
        long deadline = System.currentTimeMillis() + 10_000;
        while (publisher.subscriberCount() > before && System.currentTimeMillis() < deadline) {
            publisher.heartbeat();
            Thread.sleep(200);
        }
        assertThat(publisher.subscriberCount()).isEqualTo(before);
    }
}
