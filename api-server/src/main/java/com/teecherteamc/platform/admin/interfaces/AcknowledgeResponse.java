package com.teecherteamc.platform.admin.interfaces;

import com.teecherteamc.platform.event.domain.DownloadEventAcknowledgement;
import java.time.Instant;
import java.util.UUID;

/** 확인 처리 응답. acknowledgedBy는 처음 확인한 관리자의 username이다. */
public record AcknowledgeResponse(UUID eventId, String acknowledgedBy, Instant acknowledgedAt) {

    static AcknowledgeResponse from(DownloadEventAcknowledgement ack) {
        return new AcknowledgeResponse(ack.eventId(), ack.acknowledgedBy(), ack.acknowledgedAt());
    }
}
