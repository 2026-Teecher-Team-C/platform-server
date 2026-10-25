package com.teecherteamc.platform.admin.application;

/** 로그인·refresh 때 발급하는 토큰 한 쌍. */
public record AdminTokens(String accessToken, String refreshToken) {
}
