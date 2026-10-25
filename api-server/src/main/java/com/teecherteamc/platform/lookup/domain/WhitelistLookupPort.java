package com.teecherteamc.platform.lookup.domain;

import java.time.Instant;

/**
 * hash_whitelist를 읽기 전용으로 조회하는 포트. BlacklistLookupPort와 같은 이유로 WhitelistEntry
 * 애그리거트를 로딩하지 않고 전용 쿼리로 직접 읽는다. 쓰기(등록/해제)는 hashlist 컨텍스트 소관이다.
 */
public interface WhitelistLookupPort {

    /**
     * now 시점에 유효한 예외가 있나? 활성이면서 만료 전이어야 한다(만료 시각 정각은 무효).
     * WhitelistEntry.isEffectiveAt과 같은 규칙이다.
     */
    boolean isEffective(String sha256, Instant now);
}
