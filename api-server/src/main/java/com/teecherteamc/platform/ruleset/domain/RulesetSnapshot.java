package com.teecherteamc.platform.ruleset.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** 엔진에 적재할 룰셋 한 벌: 버전, 룰 원문, 꺼진 룰 이름. */
public record RulesetSnapshot(int version, String source, List<String> disabledRuleNames) {

    /** "버전:꺼진 룰 집합의 해시 앞 16자". 꺼진 룰의 순서와 무관하다. */
    public String key() {
        return keyOf(version, disabledRuleNames);
    }

    /** 원문 없이 버전과 꺼진 룰 이름만으로 key()와 같은 키를 만든다. */
    public static String keyOf(int version, List<String> disabledRuleNames) {
        String joined = String.join("\n", disabledRuleNames.stream().sorted().toList());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8));
            return version + ":" + HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
