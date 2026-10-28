package com.teecherteamc.platform.ruleset.domain;

/** 탐지 엔진의 룰 컴파일·룰셋 적재 호출. 둘 다 연결 실패·타임아웃이면 {@link EngineRulesetUnavailableException}. */
public interface EngineRulesetPort {

    CompileResult compile(String source);

    /** 엔진이 적재를 거부(INVALID_ARGUMENT)하면 IllegalStateException. */
    void load(RulesetSnapshot snapshot);
}
