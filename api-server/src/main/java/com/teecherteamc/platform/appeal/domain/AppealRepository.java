package com.teecherteamc.platform.appeal.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * 소명 저장소가 할 수 있어야 하는 일의 약속. 구현은 infrastructure의 AppealRepositoryAdapter가 한다.
 */
public interface AppealRepository {

    /**
     * 새 소명을 저장하고 바로 DB에 반영한다. 같은 event_id가 이미 있으면 uq_appeals_event 위반이
     * 커밋이 아니라 이 호출에서 DataIntegrityViolationException으로 드러난다.
     */
    Appeal saveNew(Appeal appeal);

    Optional<Appeal> findByEventId(UUID eventId);
}
