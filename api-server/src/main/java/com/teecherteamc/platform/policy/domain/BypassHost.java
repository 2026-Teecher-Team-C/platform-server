package com.teecherteamc.platform.policy.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 바이패스(검사 제외) 대상 host. 검증과 정규화(공백 제거, 소문자 변환, 끝의 점 제거)는 이 클래스에서만 한다.
 * 와일드카드는 쓸 수 없다: *.google.com은 drive.google.com까지 면제해 검사 구멍을 만든다.
 */
public final class BypassHost {

    /** 전체 길이 상한. DNS 규격이 253자이고, 컬럼(255자)에도 들어간다. */
    private static final int MAX_LENGTH = 253;

    /** 점 사이 한 토막(라벨)의 길이 상한. */
    private static final int MAX_LABEL_LENGTH = 63;

    /** 라벨 하나: 영소문자·숫자·하이픈, 하이픈으로 시작하거나 끝나지 않는다. 한 번만 만들어 재사용한다. */
    private static final Pattern LABEL_PATTERN = Pattern.compile("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$");

    /** 마지막 라벨에 영문자가 하나는 있어야 한다. 1.2.3.4 같은 IP 주소를 거르는 데 쓴다. */
    private static final Pattern HAS_LETTER = Pattern.compile("[a-z]");

    /** host에 있으면 안 되는 문자. 주소 전체(https://a.com/x)를 붙여 넣은 실수를 막는다. */
    private static final String FORBIDDEN_CHARS = "/?#@:";

    private final String value;

    /** 밖에서 직접 new 하지 못하게 private. 반드시 of()를 거쳐야 검증을 우회할 수 없다. */
    private BypassHost(String value) {
        this.value = value;
    }

    public static BypassHost of(String raw) {
        if (raw == null) {
            throw new InvalidBypassDomainException("host가 비어 있습니다.");
        }
        // Locale.ROOT: 컴퓨터의 언어 설정과 상관없이 항상 같은 규칙으로 소문자 변환
        String host = raw.trim().toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);   // "a.com." 은 "a.com" 과 같은 host다
        }
        if (host.isEmpty()) {
            throw new InvalidBypassDomainException("host가 비어 있습니다.");
        }
        if (host.contains("*")) {
            throw new InvalidBypassDomainException("host에는 와일드카드(*)를 쓸 수 없습니다.");
        }
        if (host.contains("://") || containsForbiddenChar(host)) {
            throw new InvalidBypassDomainException("host만 입력해야 합니다. (스킴, 경로, 포트, 계정 정보는 쓸 수 없습니다.)");
        }
        if (host.length() > MAX_LENGTH) {
            throw new InvalidBypassDomainException("host는 " + MAX_LENGTH + "자 이하여야 합니다.");
        }
        validateLabels(host);
        return new BypassHost(host);
    }

    private static boolean containsForbiddenChar(String host) {
        for (char c : FORBIDDEN_CHARS.toCharArray()) {
            if (host.indexOf(c) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** 점으로 나눈 각 라벨이 규칙에 맞는지, 그리고 점이 하나 이상인지 본다. */
    private static void validateLabels(String host) {
        // split의 -1: 끝에 빈 토막이 있어도 버리지 않는다. "a..com" 의 빈 토막을 잡아내기 위해서다.
        String[] labels = host.split("\\.", -1);
        if (labels.length < 2) {
            throw new InvalidBypassDomainException("host는 점이 하나 이상 있는 도메인이어야 합니다. (예: update.example.com)");
        }
        for (String label : labels) {
            if (label.length() > MAX_LABEL_LENGTH || !LABEL_PATTERN.matcher(label).matches()) {
                throw new InvalidBypassDomainException(
                        "host의 각 부분은 영소문자, 숫자, 하이픈만 쓸 수 있고 63자 이하여야 합니다.");
            }
        }
        if (!HAS_LETTER.matcher(labels[labels.length - 1]).find()) {
            throw new InvalidBypassDomainException("IP 주소는 쓸 수 없고 도메인 이름이어야 합니다.");
        }
    }

    public String value() {
        return value;
    }

    /** 두 BypassHost가 "같은 host"인지는 메모리 위치가 아니라 값으로 판단한다. */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BypassHost other)) {
            return false;
        }
        return Objects.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
