package com.teecherteamc.platform.appeal.domain;

import java.time.Instant;

/** 소명 화면에 보여 줄 차단 이벤트 정보. download_events의 decided_at·filename. 값이 없으면 null. */
public record BlockedDownload(Instant blockedAt, String filename) {
}
