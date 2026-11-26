package com.teecherteamc.platform.appeal.application;

/** 승인·거절 의견이 규칙(공백 제거 후 1~1000자, NUL 금지)에 맞지 않는다. 400으로 바꾸는 일은 컨트롤러가 맡는다. */
public class InvalidDecisionNoteException extends RuntimeException {

    public InvalidDecisionNoteException(String message) {
        super(message);
    }
}
