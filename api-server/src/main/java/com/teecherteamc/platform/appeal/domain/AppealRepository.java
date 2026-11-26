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

    /**
     * 결정용으로 행을 잠그고(SELECT … FOR UPDATE) 읽는다. 동시에 결정하면 늦은 쪽은 앞 트랜잭션이 끝날 때까지
     * 기다린 뒤 결정된 상태를 읽는다.
     */
    Optional<Appeal> findByIdForUpdate(UUID appealId);

    /** 결정된 소명을 저장하고 바로 DB에 반영한다(같은 트랜잭션의 JDBC 조회가 바뀐 값을 보게 한다). */
    Appeal save(Appeal appeal);
}
