package com.teecherteamc.platform.event.application;

import java.util.UUID;

/**
 * RegisterAgent/agent_token 인증이 아직 없어서(Sprint 2 예정) agent_id를 요청에서 신뢰할 수 없는 지금,
 * interfaces 레이어가 임시로 채워 넣는 자리표시자. 실제 인증이 들어오면 VerdictGrpcService의 해당
 * 호출부 한 줄만 Bearer 토큰에서 유도한 agent_id로 바꾸면 된다 — usecase/entity는 이미 진짜 agentId를
 * 받는 모양이라 변경이 필요 없다.
 */
public final class PlaceholderAgent {

    public static final UUID DEV_AGENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private PlaceholderAgent() {}
}
