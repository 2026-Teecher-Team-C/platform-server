package com.teecherteamc.platform.fleet.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 등록 토큰·agent_token은 평문을 저장하지 않는다 — 들어온 값을 해시해서 저장된 해시와 비교한다.
 * 고엔트로피 랜덤 토큰이라 비밀번호용 느린 해시(BCrypt 등)는 불필요하고, FileSpoolPortImpl이
 * sha256 계산에 쓰는 것과 같은 MessageDigest+HexFormat 조합을 그대로 쓴다. 비교는 반드시
 * matches()로 하고 String.equals()를 쓰지 않는다 — 타이밍 공격 표면을 남기지 않기 위해서다.
 */
public final class TokenHash {

    private TokenHash() {}

    public static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없다", e);
        }
    }

    public static boolean matches(String storedHashHex, String candidateHashHex) {
        return MessageDigest.isEqual(
                storedHashHex.getBytes(StandardCharsets.UTF_8), candidateHashHex.getBytes(StandardCharsets.UTF_8));
    }
}
