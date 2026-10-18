package com.teecherteamc.platform.admin.interfaces;

import com.teecherteamc.platform.admin.application.AdminView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * 로그인 세션을 만들고 끊는다. 세션 저장소는 서블릿 컨테이너 기본(메모리)이라 api-server를 다시 띄우면
 * 모두 로그아웃된다.
 */
@Component
class AdminSession {

    private final SecurityContextRepository securityContextRepository;
    private final SecurityContextHolderStrategy securityContextHolderStrategy =
            SecurityContextHolder.getContextHolderStrategy();

    AdminSession(SecurityContextRepository securityContextRepository) {
        this.securityContextRepository = securityContextRepository;
    }

    /** 세션 고정 공격을 막으려고 기존 세션을 버리고 새 세션 ID로 로그인 상태를 저장한다. */
    void establish(AdminView admin, HttpServletRequest request, HttpServletResponse response) {
        HttpSession existing = request.getSession(false);
        if (existing != null) {
            existing.invalidate();
        }
        request.getSession(true);

        SecurityContext context = securityContextHolderStrategy.createEmptyContext();
        context.setAuthentication(authenticationOf(admin));
        securityContextHolderStrategy.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }

    /**
     * 컨트롤러는 이 인증의 주체를 {@code @AuthenticationPrincipal AdminPrincipal}로 받는다. 로그인 세션이든
     * AUTH_ENABLED=false의 초기 관리자든 같은 방식이다.
     */
    static Authentication authenticationOf(AdminView admin) {
        // 역할(role)별 권한은 아직 나누지 않는다(docs/auth-policy.md 1장). 로그인 여부만 본다.
        return UsernamePasswordAuthenticationToken.authenticated(
                new AdminPrincipal(admin.adminId(), admin.username()), null, List.of());
    }

    void terminate(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        securityContextHolderStrategy.clearContext();
    }
}
