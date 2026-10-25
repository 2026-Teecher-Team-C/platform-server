package com.teecherteamc.platform.admin.interfaces;

import java.util.List;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * access 토큰(JWT)을 컨트롤러가 {@code @AuthenticationPrincipal AdminPrincipal}로 받는 인증으로 바꾼다.
 */
public final class AdminAuthentication implements Converter<Jwt, AbstractAuthenticationToken> {

    /** access 토큰(JWT)의 sub(admin_id)와 username 클레임으로 주체를 만든다. */
    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        return authenticated(new AdminPrincipal(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString("username")));
    }

    private static AbstractAuthenticationToken authenticated(AdminPrincipal principal) {
        // 역할(role)별 권한은 아직 나누지 않는다(docs/auth-policy.md 1장). 로그인 여부만 본다.
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
    }
}
