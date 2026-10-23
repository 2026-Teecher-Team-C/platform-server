package com.teecherteamc.platform.config;

import com.teecherteamc.platform.common.ApiException;
import com.teecherteamc.platform.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * 보안 필터에서 난 401/403을 컨트롤러 예외와 같은 ErrorResponse로 내린다.
 * 직접 JSON을 쓰지 않고 GlobalExceptionHandler에 ApiException으로 넘겨서 형식을 한 곳에서만 만든다.
 */
final class ErrorResponseSecurityHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final HandlerExceptionResolver handlerExceptionResolver;

    ErrorResponseSecurityHandler(HandlerExceptionResolver handlerExceptionResolver) {
        this.handlerExceptionResolver = handlerExceptionResolver;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        reject(request, response, new ApiException(ErrorCode.UNAUTHORIZED));
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        reject(request, response, new ApiException(ErrorCode.FORBIDDEN));
    }

    void reject(HttpServletRequest request, HttpServletResponse response, ApiException exception)
            throws IOException {
        if (handlerExceptionResolver.resolveException(request, response, null, exception) == null) {
            response.sendError(exception.getErrorCode().getStatus().value());
        }
    }
}
