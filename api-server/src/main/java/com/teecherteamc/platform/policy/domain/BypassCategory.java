package com.teecherteamc.platform.policy.domain;

/**
 * 바이패스 사유 분류. bypass_domains.category의 CHECK 제약(PINNED, SECURITY_UPDATE)과 같은 값이다.
 */
public enum BypassCategory {
    /** 인증서 피닝 때문에 프록시 검사를 하면 연결이 깨지는 host */
    PINNED,
    /** 보안 업데이트처럼 검사가 동작을 깨뜨리는 host */
    SECURITY_UPDATE;

    /** 요청의 글자를 enum으로 바꾼다. 목록 API의 category 필터와 같은 기준(대문자 정확히 일치)이다. */
    public static BypassCategory from(String raw) {
        if (raw != null) {
            for (BypassCategory category : values()) {
                if (category.name().equals(raw)) {
                    return category;
                }
            }
        }
        throw new InvalidBypassDomainException("category는 PINNED 또는 SECURITY_UPDATE여야 합니다.");
    }
}
