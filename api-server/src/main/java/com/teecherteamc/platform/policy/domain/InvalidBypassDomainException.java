package com.teecherteamc.platform.policy.domain;

/**
 * 바이패스 도메인 입력이 규칙에 맞지 않을 때 던진다. (예: 와일드카드가 든 host, 모르는 category)
 * 웹 응답(400)으로 바꾸는 일은 이 클래스가 아니라 REST 계층이 맡는다.
 */
public class InvalidBypassDomainException extends RuntimeException {

    public InvalidBypassDomainException(String message) {
        super(message);
    }
}
