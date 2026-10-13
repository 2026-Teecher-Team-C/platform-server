package com.teecherteamc.platform.fleet.domain;

/** 제출된 등록 토큰 해시가 어떤 agent_enrollment_tokens 행과도 일치하지 않을 때. */
public class InvalidEnrollmentTokenException extends RuntimeException {

    public InvalidEnrollmentTokenException() {
        super("등록 토큰이 유효하지 않다");
    }
}
