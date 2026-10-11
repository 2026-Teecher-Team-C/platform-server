package com.teecherteamc.platform.lookup.domain;

import java.util.Optional;

/**
 * file_verdicts를 읽기 전용으로 조회하는 포트. verdict 컨텍스트의 FileVerdict 애그리거트를
 * 로딩하지 않는다 — BlacklistLookupPort와 같은 이유(조회 사슬은 전용 쿼리로 직접 읽는다).
 */
public interface VerdictCacheLookupPort {

    Optional<CachedVerdict> find(String sha256);
}
