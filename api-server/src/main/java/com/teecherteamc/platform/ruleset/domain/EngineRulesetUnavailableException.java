package com.teecherteamc.platform.ruleset.domain;

/** 엔진 룰셋 호출이 실패(연결 불가·타임아웃 등)했을 때 던진다. */
public class EngineRulesetUnavailableException extends RuntimeException {

    public EngineRulesetUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
