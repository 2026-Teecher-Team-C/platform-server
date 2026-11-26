package com.teecherteamc.platform.appeal.application;

/** 이 차단 이벤트에는 이미 소명이 있다(이벤트 1건당 1건). 409로 바꾸는 일은 컨트롤러가 맡는다. */
public class AppealAlreadySubmittedException extends RuntimeException {

    public AppealAlreadySubmittedException() {
        super("이미 소명을 제출했습니다.");
    }

    public AppealAlreadySubmittedException(Throwable cause) {
        super("이미 소명을 제출했습니다.", cause);
    }
}
