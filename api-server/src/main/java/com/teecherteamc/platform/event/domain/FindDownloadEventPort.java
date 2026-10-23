package com.teecherteamc.platform.event.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * download_events를 devices(호스트명)와 조인해 단건 상세를 읽는 포트. {@link ListDownloadEventsPort}와
 * 같은 이유로 DownloadEvent 애그리거트에 hostname을 넣지 않고 전용 읽기 행을 쓴다.
 */
public interface FindDownloadEventPort {

    Optional<DownloadEventDetailRow> findById(UUID eventId);
}
