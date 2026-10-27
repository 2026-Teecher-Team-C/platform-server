package com.teecherteamc.platform.policy.application;

/**
 * 이미 활성 상태인 host를 또 등록하려 할 때 던지는 예외.
 *
 * HTTP 상태 코드는 모른다. application 계층은 HTTP와 무관해야 해서,
 * 409로 바꾸는 일은 interfaces의 컨트롤러가 맡는다.
 *
 * RuntimeException이라 트랜잭션이 자동으로 롤백된다.
 */
public class BypassDomainConflictException extends RuntimeException {

    /** message는 그대로 응답의 message가 되므로, 사용자가 읽을 문장으로 쓴다. */
    public BypassDomainConflictException(String message) {
        super(message);
    }
}
