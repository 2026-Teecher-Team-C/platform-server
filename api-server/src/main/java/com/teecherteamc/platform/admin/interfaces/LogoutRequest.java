package com.teecherteamc.platform.admin.interfaces;

/** 로그아웃 본문. refreshToken은 선택이다 — 없으면 폐기 없이 기록만 한다. */
public record LogoutRequest(String refreshToken) {

    // 토큰이 로그·예외 메시지에 찍히지 않게 한다.
    @Override
    public String toString() {
        return "LogoutRequest[]";
    }
}
