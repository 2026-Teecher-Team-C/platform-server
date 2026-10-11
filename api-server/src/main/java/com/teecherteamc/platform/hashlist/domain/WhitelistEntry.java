package com.teecherteamc.platform.hashlist.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * hash_whitelist 애그리거트 루트. 오탐 복원/예외용이며 SHA256만 허용한다.
 * BlacklistEntry처럼 삭제하지 않고 is_active=false 로 비활성화한다.
 */
@Entity
@Table(name = "hash_whitelist")
public class WhitelistEntry {

    @EmbeddedId
    private HashKey id;

    @Column(nullable = false, columnDefinition = "text")
    private String reason;

    /** 격리된 파일을 복원하면서 등록한 경우, 그 격리 ID. 그 외에는 비어 있다. */
    @Column(name = "origin_quarantine_id")
    private UUID originQuarantineId;

    /** 화이트리스트는 누가 승인했는지가 반드시 있어야 한다. (블랙리스트와 다름) */
    @Column(name = "added_by", nullable = false)
    private UUID addedBy;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    /** 이 시각이 지나면 효력이 없어진다. null이면 무기한이다. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA 전용. 우리 코드는 register()로만 만든다. */
    protected WhitelistEntry() {
    }

    public static WhitelistEntry register(HashKey id, String reason, UUID addedBy,
                                          UUID originQuarantineId, Instant expiresAt, Instant now) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("등록 사유는 필수입니다.");
        }
        if (addedBy == null) {
            throw new IllegalArgumentException("화이트리스트는 등록자가 필수입니다.");
        }
        // TLSH 같은 유사도 해시를 허용하면 정상 파일과 비슷하게 만든 악성 파일이 통과된다
        if (id.getHashType() != HashType.SHA256) {
            throw new IllegalArgumentException("화이트리스트는 SHA256만 허용합니다.");
        }
        WhitelistEntry entry = new WhitelistEntry();
        entry.id = id;
        entry.reason = reason;
        entry.addedBy = addedBy;
        entry.originQuarantineId = originQuarantineId;
        entry.expiresAt = expiresAt;
        entry.active = true;
        entry.createdAt = now;
        return entry;
    }

    /** 비활성화한다. (화이트리스트에는 비활성화 시각 컬럼이 없다) */
    public void deactivate() {
        this.active = false;
    }

    /**
     * now 시점에 이 예외가 유효한가? 활성이면서 만료 전이어야 한다.
     * 나중에 만들 리포지토리의 existsEffective 쿼리와 같은 규칙이어야 한다.
     */
    public boolean isEffectiveAt(Instant now) {
        return active && (expiresAt == null || expiresAt.isAfter(now));
    }

    public HashKey getId() {
        return id;
    }

    public String getReason() {
        return reason;
    }

    public UUID getOriginQuarantineId() {
        return originQuarantineId;
    }

    public UUID getAddedBy() {
        return addedBy;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}