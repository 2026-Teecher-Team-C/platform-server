package com.teecherteamc.platform.admin.interfaces;

import com.teecherteamc.platform.admin.application.AdminView;
import com.teecherteamc.platform.admin.application.CurrentAdminUseCase;
import java.util.List;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * 컨트롤러는 인증 주체를 {@code @AuthenticationPrincipal AdminPrincipal}로 받는다. access 토큰이든
 * AUTH_ENABLED=false의 초기 관리자든 이 클래스가 같은 모양의 인증을 만든다.
 *
 * <p>access 토큰은 서명과 만료만 보증한다. 그래서 권한(authorities)은 토큰의 role 클레임을 믿지 않고
 * 요청마다 admin_users에서 다시 읽은 role로 만든다 — 역할을 바꾸거나 계정을 비활성화하면 바로 반영된다.
 * 비활성화됐거나 지워진 계정의 토큰은 401이다.
 */
public final class AdminAuthentication implements Converter<Jwt, AbstractAuthenticationToken> {

    private final CurrentAdminUseCase currentAdminUseCase;

    public AdminAuthentication(CurrentAdminUseCase currentAdminUseCase) {
        this.currentAdminUseCase = currentAdminUseCase;
    }

    /** access 토큰(JWT)의 sub(admin_id)로 관리자를 다시 읽어, 그 관리자의 현재 role로 인증을 만든다. */
    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        AdminView admin = currentAdminUseCase.findActive(adminIdOf(jwt))
                .orElseThrow(() -> invalidToken("비활성화됐거나 존재하지 않는 관리자의 토큰입니다."));
        return of(admin);
    }

    static AbstractAuthenticationToken of(AdminView admin) {
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + admin.role().name()));
        return UsernamePasswordAuthenticationToken.authenticated(
                new AdminPrincipal(admin.adminId(), admin.username()), null, authorities);
    }

    private static UUID adminIdOf(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw invalidToken("sub가 관리자 id 형식이 아닙니다.");
        }
    }

    private static InvalidBearerTokenException invalidToken(String description) {
        return new InvalidBearerTokenException(description, new IllegalArgumentException(description));
    }
}
