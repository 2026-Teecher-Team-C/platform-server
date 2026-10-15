package com.teecherteamc.platform.common;

import java.time.Instant;
import java.util.List;

/**
 * 에러 응답의 공통 모양. 어떤 경로로 실패하든 이 형식으로 내려간다.
 *
 * @param timestamp   에러가 난 시각
 * @param status      HTTP 상태 코드 (예: 400)
 * @param code        ErrorCode의 이름. 프론트는 이 값으로 에러를 구분한다
 * @param message     사용자에게 보여줄 문장
 * @param path        요청한 주소
 * @param fieldErrors 입력 검증 실패 시 필드별 사유. 해당 없으면 빈 배열
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        List<FieldError> fieldErrors) {

    /** 입력 검증에서 틀린 필드 하나. */
    public record FieldError(String field, String reason) {
    }

    /** 필드 에러가 없는 일반 에러. */
    public static ErrorResponse of(ErrorCode errorCode, String message, String path) {
        return new ErrorResponse(Instant.now(), errorCode.getStatus().value(),
                errorCode.name(), message, path, List.of());
    }

    /** 필드별 사유가 있는 입력 검증 에러. */
    public static ErrorResponse of(ErrorCode errorCode, String message, String path,
                                   List<FieldError> fieldErrors) {
        return new ErrorResponse(Instant.now(), errorCode.getStatus().value(),
                errorCode.name(), message, path, fieldErrors);
    }
}