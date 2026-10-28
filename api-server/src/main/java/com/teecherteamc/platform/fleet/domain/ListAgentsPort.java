package com.teecherteamc.platform.fleet.domain;

import java.util.List;

/**
 * 에이전트 목록 조회 규칙. (읽기 전용)
 * 구현(SQL)은 infrastructure의 ListAgentsPortImpl이 한다.
 *
 * 필터는 모두 선택이다. null이면 그 조건은 걸지 않는다.
 *  - status: ACTIVE / INACTIVE / REVOKED
 *  - hostname: 부분 일치(대소문자 무시)
 *  - osPlatform: WINDOWS / MACOS / LINUX
 * 정렬은 last_heartbeat_at DESC이고, 하트비트가 한 번도 안 온 에이전트는 맨 뒤다.
 */
public interface ListAgentsPort {

    /** 한 페이지 분량을 읽는다. offset은 건너뛸 개수, limit은 가져올 개수다. */
    List<AgentListRow> list(String status, String hostname, String osPlatform, long offset, int limit);

    /** 같은 조건에 맞는 전체 개수. totalElements, totalPages를 계산하는 데 쓴다. */
    long count(String status, String hostname, String osPlatform);
}