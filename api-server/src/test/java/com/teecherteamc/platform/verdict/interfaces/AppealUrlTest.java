package com.teecherteamc.platform.verdict.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.teecherteamc.platform.appeal.application.AppealLinks;
import com.teecherteamc.platform.verdict.domain.VerdictEvidence;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AppealUrlTest {

    private static final String SHA = "a".repeat(64);
    private final AppealLinks links = mock(AppealLinks.class);
    private final UUID eventId = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();
    private final VerdictEvidence evidence = VerdictEvidence.blacklist("EICAR");

    @Test
    void block_with_evidence_and_event_gets_url() {
        when(links.urlFor(eventId, SHA, agentId, evidence)).thenReturn("http://console.test/appeal/tok");

        assertThat(VerdictGrpcService.appealUrl(links, "BLOCK", evidence, eventId, SHA, agentId))
                .isEqualTo("http://console.test/appeal/tok");
    }

    @Test
    void allow_gets_empty() {
        assertThat(VerdictGrpcService.appealUrl(links, "ALLOW", evidence, eventId, SHA, agentId)).isEmpty();
        verify(links, never()).urlFor(any(), any(), any(), any());
    }

    @Test
    void block_without_evidence_gets_empty() {
        assertThat(VerdictGrpcService.appealUrl(links, "BLOCK", VerdictEvidence.none(), eventId, SHA, agentId))
                .isEmpty();
        verify(links, never()).urlFor(any(), any(), any(), any());
    }

    @Test
    void missing_event_id_gets_empty() {
        assertThat(VerdictGrpcService.appealUrl(links, "BLOCK", evidence, null, SHA, agentId)).isEmpty();
        verify(links, never()).urlFor(any(), any(), any(), any());
    }

    @Test
    void issuer_exception_gets_empty() {
        when(links.urlFor(any(), any(), any(), any())).thenThrow(new IllegalStateException("boom"));

        assertThat(VerdictGrpcService.appealUrl(links, "BLOCK", evidence, eventId, SHA, agentId)).isEmpty();
    }
}
