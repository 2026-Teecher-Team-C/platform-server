package com.teecherteamc.platform.common;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 모든 컨트롤러에서 나는 예외를 잡아 공통 ErrorResponse로 바꾼다.
 * 어떤 경로로 실패하든 응답 모양이 같아야 프론트가 한 가지 방식으로만 처리할 수 있다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 우리가 의도해서 던진 예외. ErrorCode의 상태와 메시지대로 내려간다. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex, HttpServletRequest req) {
        log.warn("ApiException {} {}: {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(ex.getErrorCode(), ex.getMessage(), req);
    }

    /** @Valid 입력 검증 실패. 틀린 필드별 사유를 fieldErrors에 담는다. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex,
                                                          HttpServletRequest req) {
        List<ErrorResponse.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new ErrorResponse.FieldError(e.getField(), e.getDefaultMessage()))
                .toList();
        ErrorCode ec = ErrorCode.INVALID_INPUT;
        return ResponseEntity.status(ec.getStatus())
                .body(ErrorResponse.of(ec, ec.getMessage(), req.getRequestURI(), fieldErrors));
    }

    /** JSON 형식이 깨졌거나 타입이 맞지 않는 요청 본문. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex,
                                                          HttpServletRequest req) {
        return build(ErrorCode.INVALID_INPUT, "요청 본문을 읽을 수 없습니다.", req);
    }

    /** 존재하지 않는 경로. 이걸 따로 잡지 않으면 아래 catch-all 때문에 404가 500으로 바뀐다. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException ex,
                                                        HttpServletRequest req) {
        return build(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.getMessage(), req);
    }

    /** 허용하지 않는 HTTP 메서드 (예: GET만 있는 주소에 POST). */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex,
                                                                HttpServletRequest req) {
        return build(ErrorCode.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED.getMessage(), req);
    }

    /**
     * 그 밖의 모든 예외. 내부 정보(DB 주소, 스택트레이스 등)는 응답에 싣지 않고 로그에만 남긴다.
     *
     * 주의: 나중에 Spring Security를 추가하면 AccessDeniedException·AuthenticationException을
     * 여기서 다시 던지거나 따로 처리해야 한다. 안 그러면 403/401이 500으로 바뀐다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception {} {}", req.getMethod(), req.getRequestURI(), ex);
        return build(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getMessage(), req);
    }

    private ResponseEntity<ErrorResponse> build(ErrorCode errorCode, String message, HttpServletRequest req) {
        return ResponseEntity.status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode, message, req.getRequestURI()));
    }
}