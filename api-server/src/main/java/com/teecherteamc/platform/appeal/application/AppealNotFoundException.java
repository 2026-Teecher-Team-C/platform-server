package com.teecherteamc.platform.appeal.application;

/**
 * 소명 토큰이 틀렸다(서명·만료·형식 구분 없음). 404로 바꾸는 일은 컨트롤러가 맡는다.
 * 어느 단계에서 틀렸는지는 알려 주지 않는다.
 */
public class AppealNotFoundException extends RuntimeException {

    public AppealNotFoundException() {
        super("소명 링크를 찾을 수 없습니다.");
    }
}
