package com.teecherteamc.platform.hashlist.application;

/**
 * 블랙리스트 등록이 충돌 때문에 거절됐다는 것을 알리는 예외.
 *
 * 두 경우에 쓴다.
 *  - 활성 화이트리스트에 같은 해시가 있을 때
 *  - 활성 블랙리스트에 이미 같은 해시가 있을 때
 *
 * HTTP 상태 코드는 모른다. application 계층은 HTTP와 무관해야 해서,
 * 409로 바꾸는 일은 interfaces의 컨트롤러가 맡는다.
 * (ApiException은 interfaces 계층에서만 던진다는 API 컨벤션을 따른다.)
 *
 * RuntimeException이라 트랜잭션이 자동으로 롤백된다.
 */
public class HashListConflictException extends RuntimeException {

    /** message는 그대로 응답의 message가 되므로, 사용자가 읽을 문장으로 쓴다. */
    public HashListConflictException(String message) {
        super(message);
    }
}