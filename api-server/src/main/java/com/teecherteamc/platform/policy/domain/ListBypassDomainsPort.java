package com.teecherteamc.platform.policy.domain;

import java.util.List;

/**
 * bypass_domains를 관리자 콘솔용 목록으로 읽는 포트. 읽기 전용이라 애그리거트를 안 거친다 —
 * {@code quarantine.domain.ListQuarantineFilesPort}와 같은 이유.
 *
 * <p>active가 true인 행이 에이전트 GetPolicy에 내려가는 바이패스 목록과 같은 집합이다.
 * GetPolicy는 페이징이 필요 없어 별도 포트 {@link ListActiveBypassHostsPort}로 읽는다. 그래서 "활성"
 * 기준(is_active = TRUE)이 두 SQL에 따로 있으므로, 한쪽을 바꾸면 다른 쪽도 같이 바꾸고
 * GetPolicyGrpcIntegrationTest의 "같은 집합" 테스트가 깨지는지 확인한다.
 */
public interface ListBypassDomainsPort {

    /**
     * @param active      null이면 활성 여부를 가리지 않는다
     * @param category    null이면 전체 (PINNED | SECURITY_UPDATE)
     * @param hostKeyword null이면 전체. 대소문자를 무시한 부분 일치
     */
    List<BypassDomainRow> list(Boolean active, String category, String hostKeyword, long offset, int limit);

    /** list()와 같은 조건의 전체 행 수. 페이지 정보(totalElements)에 쓴다. */
    long count(Boolean active, String category, String hostKeyword);
}
