package com.teecherteamc.platform.hashlist.domain;

import java.time.Instant;
import java.util.Optional;

/**
 * 화이트리스트 저장소가 할 수 있어야 하는 일의 약속.
 * 실제 DB 접근 구현은 infrastructure 폴더의 WhitelistEntryRepositoryAdapter가 한다.
 */
public interface WhitelistEntryRepository {

    /** 저장한다. 같은 해시가 이미 있으면 덮어쓰므로, 새로 등록하기 전에 findByKey로 먼저 확인해야 한다. */
    WhitelistEntry save(WhitelistEntry entry);

    /**
     * 비활성·만료된 항목까지 포함해서 찾는다.
     * 판정에 쓰면 안 되고, 중복 등록 확인이나 비활성화 같은 관리 용도다.
     */
    Optional<WhitelistEntry> findByKey(HashKey key);

    /**
     * 조회 사슬 ①이 쓰는 메서드.
     * 활성이면서 now 시점에 만료되지 않은 항목만 본다. (만료 시각이 없으면 무기한 유효)
     */
    boolean existsEffective(HashKey key, Instant now);
}