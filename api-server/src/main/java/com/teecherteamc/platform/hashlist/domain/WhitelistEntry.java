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

    /** 직원 소명을 승인하면서 등록한 경우, 그 소명 ID. 그 외에는 비어 있다. 두 출처 중 많아야 하나만 값이 있다. */
    @Column(name = "origin_appeal_id")
    private UUID originAppealId;

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
        requireReasonAndAdder(reason, addedBy);
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

    /** 소명 승인으로 새로 등록한다. 승인은 만료 기한을 안 받는다(무기한) — 복원과 같은 이유다. */
    public static WhitelistEntry registerFromAppeal(HashKey id, String reason, UUID addedBy, UUID originAppealId,
                                                    Instant now) {
        if (originAppealId == null) {
            throw new IllegalArgumentException("소명 출처가 비었습니다.");
        }
        WhitelistEntry entry = register(id, reason, addedBy, null, null, now);
        entry.originAppealId = originAppealId;
        return entry;
    }

    /** 비활성화한다. (화이트리스트에는 비활성화 시각 컬럼이 없다) */
    public void deactivate() {
        this.active = false;
    }

    /**
     * 해제했던 항목을 직접 등록으로 되살린다. PK가 (hash_type, hash_value)라 새로 INSERT할 수 없어서다.
     * 직접 등록이므로 출처(origin_quarantine_id·origin_appeal_id)는 비운다. created_at은 처음 값을 유지한다(블랙리스트와 같다).
     */
    public void reactivate(String reason, UUID addedBy, Instant expiresAt) {
        // 이미 활성인 항목을 되살리는 건 호출한 쪽의 실수다. 중복 등록은 유스케이스에서 먼저 걸러야 한다.
        revive(reason, addedBy, expiresAt, null, null);
    }

    /**
     * 격리 파일 복원으로 되살린다. reactivate()와 달리 origin_quarantine_id를 유지한다 —
     * 복원 출처를 남겨야 목록에서 "복원됨"으로 구분할 수 있다(PR#51). 복원은 만료 기한을
     * 안 받는다(무기한) — 오탐으로 확인됐는데 나중에 다시 막히게 둘 이유가 없다.
     */
    public void restoreFromQuarantine(String reason, UUID addedBy, UUID originQuarantineId) {
        revive(reason, addedBy, null, originQuarantineId, null);
    }

    /**
     * 소명 승인으로 되살린다. 출처를 이번 소명(origin_appeal_id)으로 바꾸고, 만료 기한 없이(무기한) 되살린다.
     * 이전 격리 출처는 지운다 — 출처는 마지막으로 되살린 쪽이다.
     */
    public void restoreFromAppeal(String reason, UUID addedBy, UUID originAppealId) {
        if (originAppealId == null) {
            throw new IllegalArgumentException("소명 출처가 비었습니다.");
        }
        revive(reason, addedBy, null, null, originAppealId);
    }

    private void revive(String reason, UUID addedBy, Instant expiresAt, UUID originQuarantineId,
                        UUID originAppealId) {
        if (active) {
            throw new IllegalStateException("이미 활성 상태입니다.");
        }
        requireReasonAndAdder(reason, addedBy);
        this.reason = reason;
        this.addedBy = addedBy;
        this.expiresAt = expiresAt;
        this.originQuarantineId = originQuarantineId;
        this.originAppealId = originAppealId;
        this.active = true;
    }

    private static void requireReasonAndAdder(String reason, UUID addedBy) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("등록 사유는 필수입니다.");
        }
        if (addedBy == null) {
            throw new IllegalArgumentException("화이트리스트는 등록자가 필수입니다.");
        }
    }

    /**
     * now 시점에 이 예외가 유효한가? 활성이면서 만료 전이어야 한다.
     * 만료 시각 정각은 유효하지 않다. (expiresAt이 now보다 뒤일 때만 유효)
     * lookup의 화이트리스트 전용 쿼리도 같은 규칙을 써야 한다.
     */
    public boolean isEffectiveAt(Instant now) {
        return active && (expiresAt == null || expiresAt.isAfter(now));
    }

    /** 등록 사유만 바꾼다. 활성 여부 확인은 유스케이스가 한다. */
    public void changeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("등록 사유는 필수입니다.");
        }
        this.reason = reason;
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

    public UUID getOriginAppealId() {
        return originAppealId;
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