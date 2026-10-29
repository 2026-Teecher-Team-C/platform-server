package com.teecherteamc.platform.admin.interfaces;

import com.teecherteamc.platform.event.application.EventStreamPublisher;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 관리자 콘솔의 차단 이벤트 실시간 스트림(SSE). 인증은 다른 관리 API와 같은 Bearer 토큰이고
 * (SecurityConfig의 anyRequest().authenticated()), 브라우저 기본 EventSource는 Authorization 헤더를 못 붙이므로
 * 콘솔은 헤더를 붙일 수 있는 fetch 기반 SSE 클라이언트로 연결한다. 역할(VIEWER+) 구분은 다른 관리 API와 마찬가지로
 * 아직 하지 않는다.
 */
@RestController
public class EventStreamController {

    private final EventStreamPublisher eventStreamPublisher;

    public EventStreamController(EventStreamPublisher eventStreamPublisher) {
        this.eventStreamPublisher = eventStreamPublisher;
    }

    @GetMapping(value = "/api/admin/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return eventStreamPublisher.subscribe();
    }
}
