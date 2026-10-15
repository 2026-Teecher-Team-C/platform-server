package com.teecherteamc.platform.hashlist.domain;

import java.util.Optional;

/**
 * 화이트리스트 저장소가 할 수 있어야 하는 일의 약속.
 * 실제 DB 접근 구현은 infrastructure 폴더의 WhitelistEntryRepositoryAdapter가 한다.
 */
public interface WhitelistEntryRepository {

    /** 저장한다. 같은 해시가 이미 있으면 덮어쓰므로, 새로 등록하기 전에 findByKey로 먼저 확인해야 한다. */
    WhitelistEntry save(WhitelistEntry entry);

        /**
     * 비활성·만료 항목까지 포함해서 찾는다.
     * 해시 판정은 lookup이 전용 쿼리로 하므로, 여기서는 중복 등록 확인이나 비활성화 같은 관리 용도로만 쓴다.
     */
    Optional<WhitelistEntry> findByKey(HashKey key);

}