package com.teecherteamc.platform.admin.interfaces;

import com.teecherteamc.platform.admin.application.AdminView;
import java.util.List;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 컨트롤러는 인증 주체를 {@code @AuthenticationPrincipal AdminPrincipal}로 받는다. access 토큰이든
 * AUTH_ENABLED=false의 초기 관리자든 이 클래스가 같은 모양의 인증을 만든다.
 */
public final class AdminAuthentication implements Converter<Jwt, AbstractAuthenticationToken> {

    /** access 토큰(JWT)의 sub(admin_id)와 username 클레임으로 주체를 만든다. */
    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        return authenticated(new AdminPrincipal(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString("username")));
    }

    static AbstractAuthenticationToken of(AdminView admin) {
        return authenticated(new AdminPrincipal(admin.adminId(), admin.username()));
    }

    private static AbstractAuthenticationToken authenticated(AdminPrincipal principal) {
        // 역할(role)별 권한은 아직 나누지 않는다(docs/auth-policy.md 1장). 로그인 여부만 본다.
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
    }
}
