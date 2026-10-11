package com.teecherteamc.platform.hashlist.domain;

import java.util.Optional;

/**
 * 블랙리스트 저장소가 할 수 있어야 하는 일의 약속.
 * 실제 DB 접근 구현은 infrastructure 폴더의 BlacklistEntryRepositoryAdapter가 한다.
 */
public interface BlacklistEntryRepository {

    /** 저장한다. 같은 해시가 이미 있으면 덮어쓰므로, 새로 등록하기 전에 findByKey로 먼저 확인해야 한다. */
    BlacklistEntry save(BlacklistEntry entry);

    /**
     * 비활성 항목까지 포함해서 찾는다.
     * 판정에 쓰면 안 되고, 중복 등록 확인이나 비활성화 같은 관리 용도다.
     */
    Optional<BlacklistEntry> findByKey(HashKey key);

    /** 조회 사슬이 쓰는 메서드. 활성 항목만 본다. 비활성화된 해시는 false다. */
    boolean existsActive(HashKey key);
}