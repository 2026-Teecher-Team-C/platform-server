package com.teecherteamc.platform.event.application;

import com.teecherteamc.platform.event.domain.DownloadBlockedEvent;
import com.teecherteamc.platform.event.domain.FindAgentHostnamePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * reportEvent 트랜잭션이 커밋된 뒤에 차단 이벤트를 SSE 구독자에게 보낸다. 롤백되면 호출되지 않는다.
 */
@Component
public class DownloadBlockedEventListener {

    static final String EVENT_NAME = "download-event";

    private static final Logger log = LoggerFactory.getLogger(DownloadBlockedEventListener.class);

    private final FindAgentHostnamePort findAgentHostnamePort;
    private final EventStreamPublisher eventStreamPublisher;

    public DownloadBlockedEventListener(
            FindAgentHostnamePort findAgentHostnamePort, EventStreamPublisher eventStreamPublisher) {
        this.findAgentHostnamePort = findAgentHostnamePort;
        this.eventStreamPublisher = eventStreamPublisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(DownloadBlockedEvent event) {
        // 이벤트는 이미 커밋됐다. 알림 실패가 ReportEvent 호출을 실패시키면 에이전트가 같은 이벤트를 다시
        // 보내므로, 예외를 밖으로 내보내지 않고 로그만 남긴다.
        try {
            String hostname = findAgentHostnamePort.findHostname(event.agentId()).orElseGet(() -> {
                log.warn("SSE 알림용 hostname을 찾지 못해 agentId로 대신한다. agentId={}", event.agentId());
                return event.agentId().toString();
            });
            eventStreamPublisher.publish(EVENT_NAME, DownloadEventStreamMessage.of(event, hostname));
        } catch (RuntimeException e) {
            log.error("차단 이벤트 SSE 전송에 실패했다. eventId={}", event.eventId(), e);
        }
    }
}
