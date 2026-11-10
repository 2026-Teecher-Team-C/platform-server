package com.teecherteamc.platform.event.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 차단 이벤트 확인 처리용 저장소. 확인 상태는 download_event_acknowledgements 행의 존재로 표현한다(V7).
 * 애그리거트(DownloadEvent)를 로딩하지 않고 전용 쿼리로 처리한다 — 확인은 이벤트 내용을 바꾸지 않는다.
 */
public interface AcknowledgeDownloadEventPort {

    /**
     * 차단 이벤트(decision이 BLOCKED 또는 FAIL_CLOSE)인지. 이벤트가 없으면 empty다.
     * 아직 판정 전(decision NULL)이거나 통과·바이패스면 false다.
     */
    Optional<Boolean> isBlocked(UUID eventId);

    /**
     * 확인 행을 넣는다. 이미 있으면 아무것도 하지 않는다(INSERT … ON CONFLICT DO NOTHING).
     *
     * @return 이번 호출이 처음 넣었으면 true
     */
    boolean insertIfAbsent(UUID eventId, UUID adminId, Instant acknowledgedAt);

    /** 확인 기록. insertIfAbsent 뒤에 부르므로 항상 있다. */
    DownloadEventAcknowledgement find(UUID eventId);
}
