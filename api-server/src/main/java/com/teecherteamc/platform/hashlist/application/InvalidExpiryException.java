package com.teecherteamc.platform.hashlist.application;

/** 화이트리스트 만료 시각이 현재보다 앞이라 등록을 거절했다는 예외. 400으로 바꾸는 일은 컨트롤러가 맡는다. */
public class InvalidExpiryException extends RuntimeException {

    public InvalidExpiryException() {
        super("만료 시각은 현재보다 뒤여야 합니다.");
    }
}
