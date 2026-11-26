package com.teecherteamc.platform.appeal.application;

/** 이미 승인·거절된 소명이다(동시 결정의 늦은 쪽 포함). 409로 바꾸는 일은 컨트롤러가 맡는다. */
public class AppealAlreadyDecidedException extends RuntimeException {

    public AppealAlreadyDecidedException() {
        super("다른 담당자가 먼저 처리했습니다");
    }
}
