package com.teecherteamc.platform.hashlist.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * hash_blacklist 애그리거트 루트.
 * 삭제하지 않고 is_active=false 로 비활성화한다. (누가 언제 등록·해제했는지 이력을 남기기 위해서)
 */
@Entity
@Table(name = "hash_blacklist")   // 클래스 이름(BlacklistEntry)과 테이블 이름이 달라서 직접 연결한다
public class BlacklistEntry {

    @EmbeddedId   // 기본키가 컬럼 2개(hash_type, hash_value)라서 HashKey로 묶어 쓴다
    private HashKey id;

    @Column(nullable = false, columnDefinition = "text")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private EntrySource source;

    /** 사람이 등록한 경우에만 값이 있다. 비어 있을 수 있어서 nullable 기본값(true)을 그대로 둔다. */
    @Column(name = "added_by")
    private UUID addedBy;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    /** updatable = false: 한 번 저장된 뒤에는 UPDATE 문에서 제외한다. 등록 시각은 바뀌면 안 된다. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    /** JPA가 DB에서 읽은 행으로 객체를 만들 때 쓴다. 우리 코드는 register()로만 만든다. */
    protected BlacklistEntry() {
    }

    /** 새 블랙리스트 항목을 만든다. 현재 시각은 밖에서 받는다. (안에서 직접 구하면 테스트하기 어렵다) */
    public static BlacklistEntry register(HashKey id, String reason, Severity severity,
                                          EntrySource source, UUID addedBy, Instant now) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("등록 사유는 필수입니다.");
        }
        BlacklistEntry entry = new BlacklistEntry();
        entry.id = id;
        entry.reason = reason;
        entry.severity = severity;
        entry.source = source;
        entry.addedBy = addedBy;
        entry.active = true;        // 등록하면 바로 활성 상태
        entry.createdAt = now;
        return entry;
    }

    /** 삭제 대신 비활성화한다. 행은 남고, 조회 쿼리는 is_active = true 인 것만 본다. */
    public void deactivate(Instant now) {
        this.active = false;
        this.deactivatedAt = now;
    }

        /**
     * 해제했던 해시를 다시 등록한다. 행을 새로 만들지 않고 기존 행을 되살린다.
     * PK가 (hash_type, hash_value)라서 같은 해시를 INSERT하면 충돌하기 때문이다.
     *
     * 사유·심각도·출처·등록자는 이번 등록 기준으로 덮어쓴다.
     * createdAt은 updatable = false라서 그대로 남는다. (최초 등록 시각)
     */
    public void reactivate(String reason, Severity severity, EntrySource source, UUID addedBy) {
        // 이미 활성인 항목을 되살리는 건 호출한 쪽의 실수다. 중복 등록은 유스케이스에서 먼저 걸러야 한다.
        if (active) {
            throw new IllegalStateException("이미 활성 상태입니다.");
        }
        // register()와 같은 규칙: 사유는 필수
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("등록 사유는 필수입니다.");
        }
        this.reason = reason;
        this.severity = severity;
        this.source = source;
        this.addedBy = addedBy;
        this.active = true;
        this.deactivatedAt = null;   // 해제 시각을 지워서 다시 활성 상태로 돌린다
    }

        /**
     * 사유만 바꾼다. 심각도·출처·등록자·등록 시각·활성 여부는 그대로 둔다.
     *
     * 해제된 항목(is_active = false)은 고칠 수 없다.
     * 카드에 "활성 항목이 없으면 404"라고 되어 있어서, 유스케이스가 활성 항목만 찾아 넘기는 게 정상 경로다.
     * 여기서 한 번 더 막는 건, 이 메서드를 다른 곳에서 잘못 불렀을 때 해제된 항목이 조용히 바뀌는 걸 막기 위해서다.
     */
    public void changeReason(String reason) {
        if (!active) {
            throw new IllegalStateException("해제된 항목의 사유는 바꿀 수 없습니다.");
        }
        // register(), reactivate()와 같은 규칙: 사유는 필수
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

    public Severity getSeverity() {
        return severity;
    }

    public EntrySource getSource() {
        return source;
    }

    public UUID getAddedBy() {
        return addedBy;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDeactivatedAt() {
        return deactivatedAt;
    }
}