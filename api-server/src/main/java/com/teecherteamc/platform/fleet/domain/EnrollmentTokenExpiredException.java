package com.teecherteamc.platform.fleet.domain;

/** 만료 시각이 지난 등록 토큰으로 RegisterAgent를 호출했을 때. */
public class EnrollmentTokenExpiredException extends RuntimeException {

    public EnrollmentTokenExpiredException() {
        super("등록 토큰이 만료됐다");
    }
}
