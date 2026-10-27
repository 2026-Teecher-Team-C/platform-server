package com.teecherteamc.platform.policy.application;

/**
 * 해제할 활성 바이패스 도메인이 없다는 예외. 404로 바꾸는 일은 컨트롤러가 맡는다.
 * (BypassDomainConflictException과 같은 이유로 HTTP를 모른다)
 *
 * 없는 id와 이미 해제된 id를 구분하지 않고 같은 예외로 다룬다. (화이트리스트 해제와 같은 규칙)
 */
public class BypassDomainNotFoundException extends RuntimeException {

    public BypassDomainNotFoundException() {
        super("등록된 바이패스 도메인이 아닙니다.");
    }
}
