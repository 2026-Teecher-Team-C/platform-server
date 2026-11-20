package com.teecherteamc.platform.admin.interfaces;

import com.teecherteamc.platform.hashlist.domain.AffectedDeviceRow;
import java.time.Instant;
import java.util.UUID;

/** 해시를 실제로 받은 적 있는 기기 한 건. IMP-10: 블랙리스트 등록 응답에 실어 보낸다. */
public record AffectedDeviceItem(UUID deviceId, String hostname, int receivedCount, Instant lastReceivedAt) {

    static AffectedDeviceItem from(AffectedDeviceRow row) {
        return new AffectedDeviceItem(row.deviceId(), row.hostname(), row.receivedCount(), row.lastReceivedAt());
    }
}
