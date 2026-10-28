package com.teecherteamc.platform.common;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 모든 컨트롤러에서 나는 예외를 잡아 공통 ErrorResponse로 바꾼다.
 * 어떤 경로로 실패하든 응답 형식이 같아야 프론트가 한 가지 방식으로만 처리할 수 있다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 우리가 의도해서 던진 예외. ErrorCode의 상태대로 내려간다.
     * 5xx는 던진 쪽이 쓴 메시지를 응답에 싣지 않는다(내부 정보가 새는 것을 막기 위해).
     * 원인은 로그에만 남기고, 응답에는 ErrorCode의 기본 메시지를 쓴다.
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex, HttpServletRequest req) {
        ErrorCode code = ex.getErrorCode();

        if (code.getStatus().is5xxServerError()) {
            log.error("ApiException {} {}: {}", req.getMethod(), req.getRequestURI(), ex.getMessage(), ex);
            return build(code, code.getMessage(), req);
        }

        log.warn("ApiException {} {}: {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(code, ex.getMessage(), req);
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

    /** multipart 파일이 spring.servlet.multipart 크기 제한을 넘음. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadTooLarge(MaxUploadSizeExceededException ex,
                                                              HttpServletRequest req) {
        return build(ErrorCode.INVALID_INPUT, "파일은 1MiB 이하여야 합니다.", req);
    }

    /**
     * multipart 요청에 필요한 파트·파라미터가 없음(예: file 파트 누락). 따로 잡지 않으면 아래 catch-all 때문에
     * 클라이언트 실수가 500이 된다.
     */
    @ExceptionHandler({MissingServletRequestPartException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ErrorResponse> handleMissingPart(Exception ex, HttpServletRequest req) {
        return build(ErrorCode.INVALID_INPUT, "필요한 요청 항목이 없습니다.", req);
    }

    /**
     * multipart가 아니거나 깨진 multipart 요청. 크기 초과(MaxUploadSizeExceededException)는 위에서 따로 잡는다
     * (더 구체적인 예외 타입의 핸들러가 먼저 고른다).
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ErrorResponse> handleMultipart(MultipartException ex, HttpServletRequest req) {
        return build(ErrorCode.INVALID_INPUT, "multipart/form-data 요청이어야 합니다.", req);
    }

    /** 쿼리 파라미터 타입이 맞지 않음 (예: page=abc). 따로 잡지 않으면 아래 catch-all 때문에 500이 된다. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                            HttpServletRequest req) {
        return build(ErrorCode.INVALID_INPUT, ex.getName() + " 값의 형식이 올바르지 않습니다.", req);
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
     * 컨트롤러 안에서 난 인증 예외. 보안 필터에서 난 401은 SecurityConfig의 진입점이 처리하지만,
     * 여기서 따로 잡지 않으면 아래 catch-all 때문에 401이 500으로 바뀐다.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex, HttpServletRequest req) {
        return build(ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.getMessage(), req);
    }

    /** 컨트롤러 안에서 난 접근 거부. 위와 같은 이유로 따로 잡는다. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest req) {
        return build(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.getMessage(), req);
    }

    /** 그 밖의 모든 예외. 내부 정보(DB 주소, 스택트레이스 등)는 응답에 싣지 않고 로그에만 남긴다. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest req) {
        log.error("처리되지 않은 예외 {} {}", req.getMethod(), req.getRequestURI(), ex);
        return build(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getMessage(), req);
    }

    /** ErrorCode의 HTTP 상태와 함께 ErrorResponse를 만든다. */
    private ResponseEntity<ErrorResponse> build(ErrorCode ec, String message, HttpServletRequest req) {
        return ResponseEntity.status(ec.getStatus())
                .body(ErrorResponse.of(ec, message, req.getRequestURI()));
    }
}