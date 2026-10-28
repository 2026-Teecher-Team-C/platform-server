package com.teecherteamc.platform.common;

import org.springframework.http.HttpStatus;

/**
 * 서버가 내려주는 에러 종류 목록.
 * 프론트는 message(문장)가 아니라 name()(코드 이름)으로 에러를 구분한다.
 * 문장은 바뀔 수 있어도 이름은 바꾸지 않는다.
 */
public enum ErrorCode {

    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "허용되지 않는 요청 방식입니다."),
    CONFLICT(HttpStatus.CONFLICT, "이미 존재하거나 충돌하는 요청입니다."),
    UNPROCESSABLE(HttpStatus.UNPROCESSABLE_CONTENT, "처리할 수 없는 요청입니다."),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "잠시 후 다시 시도해 주세요."),
    // 서버 내부 오류. 상세 원인은 응답에 싣지 않고 로그에만 남긴다.
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}