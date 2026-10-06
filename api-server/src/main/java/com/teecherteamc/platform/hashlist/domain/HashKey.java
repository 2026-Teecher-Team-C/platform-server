package com.teecherteamc.platform.hashlist.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.io.Serializable;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 블랙/화이트리스트의 복합키 (hash_type, hash_value).
 * SHA-256 검증과 정규화(소문자 변환)는 이 클래스에서만 한다.
 */
@Embeddable
public class HashKey implements Serializable {

    /** 소문자 16진수 정확히 64자. 한 번만 만들어 두고 계속 재사용한다. */
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    @Enumerated(EnumType.STRING)   // enum을 번호가 아니라 글자("SHA256")로 저장한다
    @Column(name = "hash_type", nullable = false, length = 16)
    private HashType hashType;

    @Column(name = "hash_value", nullable = false, length = 128)
    private String hashValue;

    /** JPA가 DB에서 읽은 값으로 객체를 만들 때 쓴다. 우리 코드에서는 쓰지 못하게 protected. */
    protected HashKey() {
    }

    /** 밖에서 직접 new 하지 못하게 private. 반드시 sha256()을 거쳐야 검증을 우회할 수 없다. */
    private HashKey(HashType hashType, String hashValue) {
        this.hashType = hashType;
        this.hashValue = hashValue;
    }

    /** 현재는 SHA256만 만들 수 있다. 공백 제거 + 소문자 변환 후 64자리 16진수인지 검사한다. */
    public static HashKey sha256(String raw) {
        if (raw == null) {
            throw new InvalidHashException("SHA-256 값이 비어 있습니다.");
        }
        // Locale.ROOT: 컴퓨터의 언어 설정과 상관없이 항상 같은 규칙으로 소문자 변환
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (!SHA256_PATTERN.matcher(normalized).matches()) {
            throw new InvalidHashException("SHA-256은 64자리 16진수여야 합니다.");
        }
        return new HashKey(HashType.SHA256, normalized);
    }

    public HashType getHashType() {
        return hashType;
    }

    public String getHashValue() {
        return hashValue;
    }

    /** 두 HashKey가 "같은 해시"인지는 메모리 위치가 아니라 값으로 판단한다. */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HashKey other)) {
            return false;
        }
        return hashType == other.hashType && Objects.equals(hashValue, other.hashValue);
    }

    @Override
    public int hashCode() {
        return Objects.hash(hashType, hashValue);
    }
}