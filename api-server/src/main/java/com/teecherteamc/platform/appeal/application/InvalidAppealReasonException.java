package com.teecherteamc.platform.appeal.application;

/** 소명 사유가 비었거나 너무 길다. 400으로 바꾸는 일은 컨트롤러가 맡는다. */
public class InvalidAppealReasonException extends RuntimeException {

    public InvalidAppealReasonException(String message) {
        super(message);
    }
}
