package com.teecherteamc.platform.common;

/**
 * 컨트롤러·서비스(application 계층)에서 던지는 공통 예외.
 * GlobalExceptionHandler가 잡아서 ErrorCode의 HTTP 상태와 메시지로 응답을 만든다.
 *
 * domain 계층은 이 예외를 쓰지 않는다. domain은 자기 예외(예: InvalidHashException)를 던지고,
 * 바깥(interfaces)에서 이 예외나 ErrorCode로 바꿔서 내려준다.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;

    /** ErrorCode의 기본 메시지를 그대로 쓴다. */
    public ApiException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** 상황에 맞는 메시지를 직접 지정한다. 예: "이미 등록된 해시입니다." */
    public ApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}