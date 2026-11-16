package com.teecherteamc.platform.hashlist.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 블랙리스트 일괄 가져오기의 읽기·쓰기 경계. 한 건씩 엔티티를 저장하지 않고 묶음 SQL로 처리한다. */
public interface BlacklistBulkWriter {

    /**
     * 정렬된 SHA마다 eligibility 잠금을 잡고, 그다음 corpus 잠금을 잡는다. 한 건짜리 목록 쓰기와 같은 순서라
     * 교착이 생기지 않는다. 트랜잭션 안에서만 부른다.
     */
    void lockForImport(List<String> sortedSha256s);

    /** 이미 있는 SHA256 블랙리스트 행: SHA → 활성 여부. */
    Map<String, Boolean> findExisting(Collection<String> sha256s);

    /** now 기준 유효한(활성이고 만료 전인) SHA256 화이트리스트 항목. */
    Set<String> findEffectiveWhitelisted(Collection<String> sha256s, Instant now);

    /** source=IMPORT, severity=HIGH로 넣는다. 이미 있으면 건너뛴다. 실제로 넣은 SHA를 돌려준다. */
    List<String> insertImported(Map<String, String> reasonBySha, UUID addedBy, Instant now);
}
