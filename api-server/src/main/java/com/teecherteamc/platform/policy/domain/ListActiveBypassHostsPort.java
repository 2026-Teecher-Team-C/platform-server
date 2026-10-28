package com.teecherteamc.platform.policy.domain;

import java.util.List;

/**
 * 에이전트 GetPolicy에 내려갈 활성 바이패스 호스트를 읽는 포트. 읽기 전용이라 애그리거트를 안 거친다.
 *
 * <p>콘솔 목록({@link ListBypassDomainsPort})과는 일부러 분리했다. 목록은 페이징용이라 큰 limit으로 전부를
 * 읽으면 호스트가 늘 때 조용히 잘리고, 화면 정렬·컬럼이 바뀌면 에이전트 응답까지 흔들린다.
 * 두 포트의 "활성" 기준(is_active = TRUE)은 같은 집합이어야 한다.
 */
public interface ListActiveBypassHostsPort {

    /**
     * is_active가 true인 모든 행을 host 오름차순으로 돌려준다. 없으면 빈 목록이다.
     *
     * @throws IllegalStateException category가 {@link BypassCategory}에 없는 값일 때
     */
    List<ActiveBypassHost> listAll();
}
