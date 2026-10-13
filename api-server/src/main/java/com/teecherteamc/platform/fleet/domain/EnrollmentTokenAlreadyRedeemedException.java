package com.teecherteamc.platform.fleet.domain;

/** 이미 한 번 소비된 등록 토큰으로 다시 RegisterAgent를 호출했을 때(재사용 방지). */
public class EnrollmentTokenAlreadyRedeemedException extends RuntimeException {

    public EnrollmentTokenAlreadyRedeemedException() {
        super("등록 토큰이 이미 사용됐다");
    }
}
