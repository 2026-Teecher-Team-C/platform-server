package com.teecherteamc.platform.lookup.domain;

import java.util.Optional;

/**
 * hash_blacklist를 읽기 전용으로 조회하는 포트. hashlist 컨텍스트의 BlacklistEntry 애그리거트를
 * 로딩하지 않는다 — CheckHash는 다운로드마다 호출되는 가장 뜨거운 경로라, 조회 사슬은 애그리거트 안
 * 거치고 전용 쿼리로 직접 읽는다(package-info 참고). 쓰기(등록/해제)는 hashlist 컨텍스트 소관이다.
 */
public interface BlacklistLookupPort {

    /** 활성 상태인 매치만 반환한다(is_active=false는 빈 값). 있으면 차단 사유. */
    Optional<String> findActiveReason(String sha256);
}
