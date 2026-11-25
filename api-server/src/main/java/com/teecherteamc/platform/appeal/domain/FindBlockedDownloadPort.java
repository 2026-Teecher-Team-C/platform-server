package com.teecherteamc.platform.appeal.domain;

import java.util.Optional;
import java.util.UUID;

/** download_events(event 컨텍스트 소유)를 읽기만 하는 포트. 애그리거트를 로딩하지 않는다. */
public interface FindBlockedDownloadPort {

    /** event_id와 agent_id가 둘 다 맞는 행만 찾는다. 다른 에이전트의 이벤트는 없는 것으로 본다. */
    Optional<BlockedDownload> find(UUID eventId, UUID agentId);
}
