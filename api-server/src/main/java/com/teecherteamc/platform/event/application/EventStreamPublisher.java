package com.teecherteamc.platform.event.application;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 콘솔 SSE 구독자(SseEmitter) 목록을 메모리에 들고 있다가 이벤트를 모두에게 보낸다. EC2가 한 대라 서버 간
 * 공유는 필요 없다 — 서버가 여러 대가 되면 이 구조로는 다른 서버에 붙은 구독자에게 못 보낸다.
 *
 * <p>SSE는 알림 채널이고 원장은 download_events다. 연결이 끊긴 동안의 이벤트는 여기서 보충하지 않는다.
 * 콘솔이 다시 연결한 뒤 목록 API로 맞춘다.
 */
@Component
public class EventStreamPublisher {

    // access 토큰 TTL(1시간)보다 짧게 둬서, 한 번 인증한 연결이 토큰 수명보다 오래 살아남지 않게 한다.
    private static final long EMITTER_TIMEOUT_MS = Duration.ofMinutes(30).toMillis();
    private static final long HEARTBEAT_INTERVAL_MS = 30_000;

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> emitters.remove(emitter));
        // 첫 바이트를 바로 보내야 응답 헤더가 나가서 클라이언트가 "연결됨"을 알 수 있다.
        send(emitter, SseEmitter.event().comment("connected"));
        return emitter;
    }

    public void publish(String eventName, Object data) {
        for (SseEmitter emitter : emitters) {
            send(emitter, SseEmitter.event().name(eventName).data(data));
        }
    }

    /** nginx·브라우저가 조용한 연결을 끊지 않도록 주기적으로 주석 줄(:keepalive)을 보낸다. */
    @Scheduled(fixedRate = HEARTBEAT_INTERVAL_MS)
    void heartbeat() {
        for (SseEmitter emitter : emitters) {
            send(emitter, SseEmitter.event().comment("keepalive"));
        }
    }

    int subscriberCount() {
        return emitters.size();
    }

    private void send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            // 끊긴 연결(IOException)이거나 이미 끝난 emitter(IllegalStateException)다. 목록에서 뺀다.
            emitters.remove(emitter);
        }
    }
}
