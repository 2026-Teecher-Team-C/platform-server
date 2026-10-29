package com.teecherteamc.platform.event.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.teecherteamc.platform.event.domain.DownloadBlockedEvent;
import com.teecherteamc.platform.event.domain.FindAgentHostnamePort;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DownloadBlockedEventListenerTest {

    private final FindAgentHostnamePort hostnamePort = mock(FindAgentHostnamePort.class);
    private final EventStreamPublisher publisher = mock(EventStreamPublisher.class);
    private final DownloadBlockedEventListener listener = new DownloadBlockedEventListener(hostnamePort, publisher);

    private final UUID agentId = UUID.randomUUID();
    private final DownloadBlockedEvent event = new DownloadBlockedEvent(
            UUID.randomUUID(), agentId, null, "f.exe", "application/octet-stream", 1024L,
            "BLOCKED", "ENGINE", false, 100, Instant.parse("2026-10-29T00:00:00Z"));

    @Test
    void hostname을_찾으면_download_event로_보낸다() {
        when(hostnamePort.findHostname(agentId)).thenReturn(Optional.of("DESKTOP-1"));

        listener.on(event);

        ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publish(eq("download-event"), data.capture());
        DownloadEventStreamMessage message = (DownloadEventStreamMessage) data.getValue();
        assertThat(message.hostname()).isEqualTo("DESKTOP-1");
        assertThat(message.eventId()).isEqualTo(event.eventId());
        assertThat(message.createdAt()).isEqualTo(event.occurredAt());
    }

    @Test
    void hostname을_못_찾으면_agentId로_대신해서_보낸다() {
        when(hostnamePort.findHostname(agentId)).thenReturn(Optional.empty());

        listener.on(event);

        ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publish(eq("download-event"), data.capture());
        assertThat(((DownloadEventStreamMessage) data.getValue()).hostname()).isEqualTo(agentId.toString());
    }

    @Test
    void 전송이_실패해도_예외를_밖으로_내보내지_않는다() {
        when(hostnamePort.findHostname(agentId)).thenReturn(Optional.of("DESKTOP-1"));
        doThrow(new IllegalStateException("boom")).when(publisher).publish(any(), any());

        assertThatCode(() -> listener.on(event)).doesNotThrowAnyException();
    }

    @Test
    void hostname_조회가_실패해도_예외를_밖으로_내보내지_않는다() {
        when(hostnamePort.findHostname(agentId)).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> listener.on(event)).doesNotThrowAnyException();
    }
}
