package com.teecherteamc.platform.verdict.domain;

/** 탐지 엔진 호출이 실패(타임아웃·연결 불가 등)했을 때 던진다. 호출자는 fail-close(BLOCK)로 취급한다. */
public class EngineUnavailableException extends RuntimeException {

    public EngineUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
