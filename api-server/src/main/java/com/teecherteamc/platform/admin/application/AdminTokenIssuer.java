package com.teecherteamc.platform.admin.application;

import com.teecherteamc.platform.admin.domain.RefreshTokenStore;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * access 토큰(JWT)과 refresh 토큰을 발급한다.
 *
 * <p>access: sub=admin_id, username 클레임. 권한 판단에 쓸 값(role, is_active)은 넣지 않고 매번 admin_users에서
 * 다시 읽는다 — 토큰에 넣으면 바뀐 권한이 만료 전까지 반영되지 않는다.
 * <p>refresh: JWT가 아니라 랜덤 문자열이다. 그래서 access 토큰 자리에 넣어도 서명 검증에서 걸러진다.
 */
@Service
public class AdminTokenIssuer {

    private static final int REFRESH_TOKEN_BYTES = 32;

    private final JwtEncoder jwtEncoder;
    private final RefreshTokenStore refreshTokenStore;
    private final Duration accessTokenTtl;
    private final Duration refreshTokenTtl;
    private final SecureRandom secureRandom = new SecureRandom();

    public AdminTokenIssuer(JwtEncoder jwtEncoder, RefreshTokenStore refreshTokenStore,
                            @Value("${platform.auth.jwt.access-token-ttl}") Duration accessTokenTtl,
                            @Value("${platform.auth.jwt.refresh-token-ttl}") Duration refreshTokenTtl) {
        this.jwtEncoder = jwtEncoder;
        this.refreshTokenStore = refreshTokenStore;
        this.accessTokenTtl = accessTokenTtl;
        this.refreshTokenTtl = refreshTokenTtl;
    }

    public AdminTokens issue(AdminView admin) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(admin.adminId().toString())
                .claim("username", admin.username())
                .issuedAt(now)
                .expiresAt(now.plus(accessTokenTtl))
                .build();
        String accessToken = jwtEncoder.encode(
                JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

        byte[] random = new byte[REFRESH_TOKEN_BYTES];
        secureRandom.nextBytes(random);
        String refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        refreshTokenStore.save(refreshToken, admin.adminId(), refreshTokenTtl);

        return new AdminTokens(accessToken, refreshToken);
    }
}
