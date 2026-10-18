package com.teecherteamc.platform.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.net.InetAddress;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 감사 로그 한 줄. 테이블: audit_logs. append 전용이라 만든 뒤 바꾸는 메서드가 없다.
 *
 * <p>actor_id가 NULL이면 주체가 확정되지 않은 행위다 — LOGIN_FAILED, 또는 시스템 자동 행위.
 */
@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long logId;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "action", nullable = false, length = 48, updatable = false)
    private String action;

    @Column(name = "target_type", nullable = false, length = 32, updatable = false)
    private String targetType;

    @Column(name = "target_id", length = 128, updatable = false)
    private String targetId;

    /** JSON 문자열. 비밀번호·토큰은 넣지 않는다. */
    @Column(name = "detail", columnDefinition = "text", updatable = false)
    private String detail;

    @JdbcTypeCode(SqlTypes.INET)
    @Column(name = "ip_address", updatable = false)
    private InetAddress ipAddress;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditLog() {
        // JPA
    }

    public static AuditLog of(UUID actorIdOrNull, AuditAction action, AuditTargetType targetType,
                              String targetIdOrNull, String detailJsonOrNull, InetAddress ipAddressOrNull,
                              Instant now) {
        AuditLog log = new AuditLog();
        log.actorId = actorIdOrNull;
        log.action = action.name();
        log.targetType = targetType.name();
        log.targetId = targetIdOrNull;
        log.detail = detailJsonOrNull;
        log.ipAddress = ipAddressOrNull;
        log.createdAt = now;
        return log;
    }

    public Long getLogId() {
        return logId;
    }
}
