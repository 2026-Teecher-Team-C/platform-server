package com.teecherteamc.platform.hashlist.domain;

/**
 * 해시 값이 형식에 맞지 않을 때 던진다. (예: 64자리 16진수가 아닌 SHA-256)
 * 웹 응답(400)으로 바꾸는 일은 이 클래스가 아니라 REST 계층이 맡는다.
 */
public class InvalidHashException extends RuntimeException {

    public InvalidHashException(String message) {
        super(message);
    }
}