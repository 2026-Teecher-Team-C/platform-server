package com.teecherteamc.platform.policy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * 바이패스(검사 제외) 도메인 엔티티. 테이블: bypass_domains.
 *
 * <p>삭제하지 않고 is_active=false 로 비활성화한다. domain_pattern 의 UNIQUE 제약이 is_active 를 보지 않아서,
 * 해제했던 host 를 다시 등록하면 새 행을 넣지 못하고 기존 행을 되살려야 한다. (reactivate)
 *
 * <p>{@code Persistable}이 필요한 이유: ID(UUID)를 우리가 미리 만들어 넣는다. 그러면 Spring Data 는 "ID가 있으니
 * 이미 있는 행"이라 보고 INSERT 대신 SELECT 후 merge 를 한다. isNew() 로 새 객체임을 직접 알려 준다.
 */
@Entity
@Table(name = "bypass_domains")
public class BypassDomain implements Persistable<UUID> {

    @Id
    @Column(name = "bypass_id")
    private UUID bypassId;

    /** BypassHost 로 검증·정규화된 값이 들어간다. 컬럼 이름이 domain_pattern 일 뿐 패턴(와일드카드)이 아니다. */
    @Column(name = "domain_pattern", nullable = false, length = 255)
    private String domainPattern;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private BypassCategory category;

    @Column(nullable = false, columnDefinition = "text")
    private String reason;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    /** 등록한 관리자. 컬럼이 nullable 이라 비어 있을 수 있다. */
    @Column(name = "created_by")
    private UUID createdBy;

    /** updatable = false: 재활성화해도 최초 등록 시각은 그대로 남는다. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** DB 에서 읽은 객체는 새 객체가 아니다. 새로 만든 객체만 register() 에서 true 로 바뀐다. */
    @Transient
    private boolean isNew;

    /** JPA 가 DB 에서 읽은 행으로 객체를 만들 때 쓴다. 우리 코드는 register() 로만 만든다. */
    protected BypassDomain() {
    }

    /** 새 바이패스 도메인을 만든다. 현재 시각은 밖에서 받는다. (안에서 직접 구하면 테스트하기 어렵다) */
    public static BypassDomain register(BypassHost host, BypassCategory category, String reason, UUID createdBy,
            Instant now) {
        requireReason(reason);
        BypassDomain domain = new BypassDomain();
        domain.bypassId = UUID.randomUUID();
        domain.domainPattern = host.value();
        domain.category = category;
        domain.reason = reason;
        domain.active = true;
        domain.createdBy = createdBy;
        domain.createdAt = now;
        domain.isNew = true;
        return domain;
    }

    /**
     * 해제했던 host 를 다시 등록한다. 행을 새로 만들지 않고 기존 행을 되살린다.
     * category·reason·createdBy 는 이번 등록 기준으로 덮어쓴다. createdAt 은 그대로 남는다.
     */
    public void reactivate(BypassCategory category, String reason, UUID createdBy) {
        // 이미 활성인 host 를 되살리는 건 호출한 쪽의 실수다. 중복 등록은 유스케이스에서 먼저 걸러야 한다.
        if (active) {
            throw new IllegalStateException("이미 활성 상태입니다.");
        }
        requireReason(reason);
        this.category = category;
        this.reason = reason;
        this.createdBy = createdBy;
        this.active = true;
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new InvalidBypassDomainException("reason은 필수입니다.");
        }
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public UUID getId() {
        return bypassId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public String getDomainPattern() {
        return domainPattern;
    }

    public BypassCategory getCategory() {
        return category;
    }

    public String getReason() {
        return reason;
    }

    public boolean isActive() {
        return active;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
