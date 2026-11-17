package com.teecherteamc.platform.hashlist.domain;

import java.time.Instant;
import java.util.UUID;

/** 특정 해시를 실제로 받은 적 있는 기기 한 건. IMP-10: 악성 지정 시 영향받은 기기 조회용. */
public record AffectedDeviceRow(UUID deviceId, String hostname, int receivedCount, Instant lastReceivedAt) {
}
