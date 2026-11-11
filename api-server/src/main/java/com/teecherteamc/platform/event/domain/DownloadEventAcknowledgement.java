package com.teecherteamc.platform.event.domain;

import java.time.Instant;
import java.util.UUID;

/** 차단 이벤트를 누가·언제 확인했는지. acknowledgedBy는 확인한 관리자의 username이다. */
public record DownloadEventAcknowledgement(UUID eventId, String acknowledgedBy, Instant acknowledgedAt) {
}
