package com.teecherteamc.platform.appeal.domain;

import java.util.Optional;
import java.util.UUID;

/** download_events(event 컨텍스트 소유)를 읽기만 하는 포트. 애그리거트를 로딩하지 않는다. */
public interface FindBlockedDownloadPort {

    /** event_id와 agent_id가 둘 다 맞는 행만 찾는다. 다른 에이전트의 이벤트는 없는 것으로 본다. */
    Optional<BlockedDownload> find(UUID eventId, UUID agentId);

    /**
     * 이 에이전트의 차단(BLOCKED) 기록인지. event_id·agent_id가 맞고 sha256이 같거나 NULL이어야 한다.
     * NULL은 ReportEvent가 file_verdicts에 없는 해시를 지운 경우다. 공격 에이전트는 남의 event_id로 된
     * 차단 기록을 가질 수 없으므로(event_id가 PK) 소유의 핵심은 event_id·agent_id 일치다.
     */
    boolean isOwnedBlock(UUID eventId, UUID agentId, String sha256);
}
