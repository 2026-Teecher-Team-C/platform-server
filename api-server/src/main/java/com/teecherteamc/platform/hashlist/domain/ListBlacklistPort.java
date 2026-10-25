package com.teecherteamc.platform.hashlist.domain;

import java.util.List;

/**
 * hash_blacklist를 admin_users(등록자 username)와 조인해 관리자 콘솔용 목록을 1회 쿼리로 읽는 포트.
 * BlacklistEntry에 username을 넣지 않기 위해(그건 admin 컨텍스트 소유 데이터다)
 * 전용 읽기 전용 행 타입({@link BlacklistListRow})을 반환한다.
 *
 * 필터가 null이면 그 조건은 걸지 않는다. 단 active는 항상 걸린다(기본값 처리는 호출하는 쪽이 한다).
 */
public interface ListBlacklistPort {

    /**
     * @param hashValuePrefix 해시 값의 앞부분(소문자 hex). 64자면 정확히 그 해시만 나온다
     * @param severity        LOW | MEDIUM | HIGH | CRITICAL
     * @param source          MANUAL | IMPORT
     * @param active          true면 활성 항목만, false면 해제된 항목만
     */
    List<BlacklistListRow> list(
            String hashValuePrefix, String severity, String source, boolean active, long offset, int limit);

    /** list()와 같은 조건의 전체 행 수. 페이지 정보(totalElements)에 쓴다. */
    long count(String hashValuePrefix, String severity, String source, boolean active);
}