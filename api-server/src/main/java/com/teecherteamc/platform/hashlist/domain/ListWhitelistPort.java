package com.teecherteamc.platform.hashlist.domain;

import java.time.Instant;
import java.util.List;

/**
 * 화이트리스트 목록 조회 포트. 등록자 username을 같이 내려야 해서 애그리거트 대신 조인 쿼리로 읽는다.
 */
public interface ListWhitelistPort {

    /**
     * @param hashPrefix     소문자 해시 앞부분. null이면 거르지 않는다
     * @param origin         RESTORED | PREREGISTERED. null이면 거르지 않는다
     * @param includeExpired false면 now 기준 만료된 행을 뺀다(isActive 값과 무관)
     */
    record Filter(String hashPrefix, String origin, boolean active, boolean includeExpired, Instant now) {
    }

    List<WhitelistRow> list(Filter filter, long offset, int limit);

    long count(Filter filter);
}
