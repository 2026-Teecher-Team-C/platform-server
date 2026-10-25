package com.teecherteamc.platform.admin.interfaces;

import com.teecherteamc.platform.admin.application.AdminTokens;

/** console/src/lib/api/auth.ts의 LoginResponse와 1:1 대응하는 응답 DTO. 로그인과 refresh가 같이 쓴다. */
public record TokenResponse(String accessToken, String refreshToken, String tokenType) {

    static TokenResponse from(AdminTokens tokens) {
        return new TokenResponse(tokens.accessToken(), tokens.refreshToken(), "Bearer");
    }
}
