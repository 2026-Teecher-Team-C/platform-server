package com.teecherteamc.platform.admin.interfaces;

import com.teecherteamc.platform.admin.application.AuthDisabledAdmin;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * AUTH_ENABLED=false일 때만 SecurityConfig가 붙이는 필터. access 토큰이 없는 요청을 초기 관리자로 인증해서
 * 컨트롤러가 인증을 켰을 때와 똑같이 {@code @AuthenticationPrincipal AdminPrincipal}로 행위자를 받게 한다.
 * 요청마다 다시 채운다. access 토큰을 실은 요청은 그 토큰의 주체를 그대로 쓴다.
 */
public final class AuthDisabledAuthenticationFilter extends OncePerRequestFilter {

    private final AuthDisabledAdmin authDisabledAdmin;
    private final SecurityContextHolderStrategy securityContextHolderStrategy =
            SecurityContextHolder.getContextHolderStrategy();

    public AuthDisabledAuthenticationFilter(AuthDisabledAdmin authDisabledAdmin) {
        this.authDisabledAdmin = authDisabledAdmin;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication current = securityContextHolderStrategy.getContext().getAuthentication();
        if (current == null || !(current.getPrincipal() instanceof AdminPrincipal)) {
            SecurityContext context = securityContextHolderStrategy.createEmptyContext();
            context.setAuthentication(AdminAuthentication.of(authDisabledAdmin.require()));
            securityContextHolderStrategy.setContext(context);
        }
        chain.doFilter(request, response);
    }
}
