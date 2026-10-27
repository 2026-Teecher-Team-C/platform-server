package com.teecherteamc.platform.hashlist.application;

/**
 * 해제·수정할 활성 화이트리스트 항목이 없다는 예외. 404로 바꾸는 일은 컨트롤러가 맡는다.
 * (HashListConflictException과 같은 이유로 HTTP를 모른다)
 */
public class WhitelistNotFoundException extends RuntimeException {

    public WhitelistNotFoundException() {
        super("화이트리스트에 등록된 해시가 아닙니다.");
    }
}
