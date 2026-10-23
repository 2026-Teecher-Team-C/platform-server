package com.teecherteamc.platform.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 관리자 행위 기록 1건 (audit_logs 한 줄). 쌓기만 하고 수정하지 않는다.
 *
 * action / target_type 값은 설계 레포의 감사 로그 포맷을 따른다.
 * ip_address(INET)는 아직 기록하지 않아 매핑하지 않았다. 컬럼이 nullable이라 INSERT에서 빠져도 된다.
 */
@Entity
@Table(name = "audit_logs")
public class AuditLog {

    // BIGSERIAL: 번호는 DB가 붙인다. IDENTITY = insert 후 DB가 만든 값을 받아온다.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long id;

    // 누가 했는지. admin_users FK이고 nullable. 로그인이 붙기 전에는 null.
    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "action", nullable = false, length = 48)
    private String action;          // 예: ADD_BLACKLIST

    @Column(name = "target_type", nullable = false, length = 32)
    private String targetType;      // 예: BLACKLIST

    @Column(name = "target_id", length = 128)
    private String targetId;        // 예: 해시 값

    // JSON 문자열(TEXT). 부가 정보가 없으면 null.
    @Column(name = "detail", columnDefinition = "text")
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // JPA가 쓰는 기본 생성자. 밖에서는 record()로만 만든다.
    protected AuditLog() {
    }

    private AuditLog(UUID actorId, String action, String targetType,
                     String targetId, String detail, Instant createdAt) {
        this.actorId = actorId;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.detail = detail;
        this.createdAt = createdAt;
    }

    public static AuditLog record(UUID actorId, String action, String targetType,
                                  String targetId, String detail, Instant now) {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("action은 필수입니다.");
        }
        if (targetType == null || targetType.isBlank()) {
            throw new IllegalArgumentException("targetType은 필수입니다.");
        }
        return new AuditLog(actorId, action, targetType, targetId, detail, now);
    }

    public Long getId() { return id; }
    public UUID getActorId() { return actorId; }
    public String getAction() { return action; }
    public String getTargetType() { return targetType; }
    public String getTargetId() { return targetId; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
}