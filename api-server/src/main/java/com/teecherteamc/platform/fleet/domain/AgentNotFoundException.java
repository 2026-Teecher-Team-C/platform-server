package com.teecherteamc.platform.fleet.domain;

/** 인터셉터가 넘긴 agent_id로 agents 행을 찾지 못했을 때 — 인증 통과 후에는 일어나지 않아야 한다. */
public class AgentNotFoundException extends RuntimeException {

    public AgentNotFoundException() {
        super("에이전트를 찾을 수 없다");
    }
}
