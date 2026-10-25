package com.teecherteamc.platform.admin.interfaces;

import jakarta.validation.constraints.NotBlank;

public record RefreshRequest(@NotBlank String refreshToken) {

    // 토큰이 로그·예외 메시지에 찍히지 않게 한다.
    @Override
    public String toString() {
        return "RefreshRequest[]";
    }
}
