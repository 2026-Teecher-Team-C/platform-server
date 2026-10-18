package com.teecherteamc.platform.fleet.domain;

/**
 * REVOKED 상태인 에이전트가 인증이 필요한 RPC를 호출했을 때. 인터셉터가 먼저 막지만,
 * 유스케이스에서도 다시 검증한다 — 이 코드베이스가 여러 레이어에서 같은 불변식을 반복
 * 확인하는 기존 컨벤션(ReportEvent의 PK/FK 분기 등)과 동일하다.
 */
public class AgentRevokedException extends RuntimeException {

    public AgentRevokedException() {
        super("에이전트가 차단됐다(REVOKED)");
    }
}
