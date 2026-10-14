package com.teecherteamc.platform.event.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * download_events를 devices(호스트명)와 조인해 관리자 콘솔용 목록을 1회 쿼리로 읽는 포트.
 * DownloadEvent 애그리거트에 hostname 필드를 넣지 않기 위해(그건 fleet 컨텍스트 소유 데이터다)
 * 전용 읽기 전용 행 타입({@link DownloadEventListRow})을 반환한다.
 */
public interface ListDownloadEventsPort {

    List<DownloadEventListRow> list(UUID agentId, String decision, Instant from, Instant to, int limit);
}
