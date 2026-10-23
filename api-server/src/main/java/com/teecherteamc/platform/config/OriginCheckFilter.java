package com.teecherteamc.platform.config;

import com.teecherteamc.platform.common.ApiException;
import com.teecherteamc.platform.common.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 세션 쿠키용 CSRF 방어. Spring Security의 CSRF 토큰 대신 Origin 헤더로 막는다.
 *
 * <p>콘솔(Vercel)과 api-server가 다른 사이트라 쿠키가 SameSite=None이어야 하고, 그러면 SameSite로는 CSRF를
 * 막을 수 없다. 쿠키 기반 CSRF 토큰도 콘솔이 다른 사이트의 쿠키를 읽을 수 없어 쓸 수 없다. 대신 브라우저는
 * 다른 출처로 보내는 POST·PUT·PATCH·DELETE에 Origin을 반드시 싣고 페이지가 이 값을 바꿀 수 없으므로,
 * 허용 목록(platform.cors.allowed-origins)에 없는 Origin의 쓰기 요청을 거부한다.
 *
 * <p>Origin이 없으면 브라우저가 아닌 클라이언트(curl 등)거나 Origin을 지운 요청이다. 세션 쿠키가 없으면
 * 훔쳐 쓸 권한이 없으니 통과시키고(로그인 요청 등), 세션 쿠키를 실었으면 거부한다.
 *
 * <p>GET은 상태를 바꾸지 않는다는 전제라 검사하지 않는다. GET으로 상태를 바꾸는 API를 만들지 않는다.
 */
final class OriginCheckFilter extends OncePerRequestFilter {

    private static final Set<String> STATE_CHANGING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final List<String> allowedOrigins;
    private final ErrorResponseSecurityHandler errorHandler;

    OriginCheckFilter(List<String> allowedOrigins, ErrorResponseSecurityHandler errorHandler) {
        this.allowedOrigins = allowedOrigins;
        this.errorHandler = errorHandler;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !STATE_CHANGING_METHODS.contains(request.getMethod()) || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        boolean rejected = origin != null
                ? !allowedOrigins.contains(origin)
                : request.getRequestedSessionId() != null;
        if (rejected) {
            errorHandler.reject(request, response, new ApiException(ErrorCode.FORBIDDEN, "허용되지 않은 출처의 요청입니다."));
            return;
        }
        chain.doFilter(request, response);
    }
}
