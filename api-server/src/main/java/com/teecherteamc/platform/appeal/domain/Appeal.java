package com.teecherteamc.platform.appeal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * 소명 애그리거트 루트. 테이블: appeals. 이벤트 1건당 1건(uq_appeals_event).
 *
 * <p>event_id는 download_events를 참조하지 않는다. 이벤트 보고가 차단 응답보다 늦거나 실패할 수 있어서,
 * 토큰에 서명된 event_id·sha256·agent_id만으로 소명이 성립한다.
 */
@Entity
@Table(name = "appeals")
public class Appeal implements Persistable<UUID> {

    @Id
    @Column(name = "appeal_id")
    private UUID appealId;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "sha256", nullable = false, length = 64, updatable = false)
    private String sha256;

    @Column(name = "agent_id", nullable = false, updatable = false)
    private UUID agentId;

    @Column(name = "reason", nullable = false, columnDefinition = "text", updatable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AppealStatus status;

    /** 제출 시 토큰의 판정 근거 종류(VerdictEvidence.Kind 이름). V22 이전 소명은 null. */
    @Column(name = "evidence_kind", length = 16, updatable = false)
    private String evidenceKind;

    /** 제출 시 토큰의 위협 이름(255자까지). 없으면 null. */
    @Column(name = "threat", length = 255, updatable = false)
    private String threat;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decision_note", columnDefinition = "text")
    private String decisionNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Transient
    private boolean isNew;

    /** JPA 전용. 우리 코드는 submit()으로만 만든다. */
    protected Appeal() {
    }

    static final int MAX_THREAT_CODE_POINTS = 255;

    /**
     * 검토 대기(PENDING) 소명을 만든다. 사유 검증(공백 제거, 1~1000자)은 유스케이스가 끝낸 값을 받는다.
     * evidenceKind·threat는 토큰의 근거 스냅숏이다. threat는 빈 값이면 null, 길면 컬럼 길이(255자)에 맞춰 자른다.
     */
    public static Appeal submit(UUID eventId, String sha256, UUID agentId, String reason, String evidenceKind,
                                String threat, Instant now) {
        if (eventId == null || agentId == null || sha256 == null || sha256.isBlank()) {
            throw new IllegalArgumentException("소명 대상 이벤트 정보가 비었습니다.");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("소명 사유는 필수입니다.");
        }
        Appeal appeal = new Appeal();
        appeal.appealId = UUID.randomUUID();
        appeal.eventId = eventId;
        appeal.sha256 = sha256;
        appeal.agentId = agentId;
        appeal.reason = reason;
        appeal.evidenceKind = evidenceKind;
        appeal.threat = threatSnapshot(threat);
        appeal.status = AppealStatus.PENDING;
        appeal.createdAt = now;
        appeal.isNew = true;
        return appeal;
    }

    private static String threatSnapshot(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.codePointCount(0, value.length()) <= MAX_THREAT_CODE_POINTS) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, MAX_THREAT_CODE_POINTS));
    }

    /**
     * 토큰의 agent_id·sha256과 같은 소명인지. event_id만 같고 이것이 다르면 다른 사람의 소명이다
     * (블랙리스트 해시만 보내도 남의 event_id로 토큰을 받을 수 있다).
     */
    public boolean isOwnedBy(UUID agentId, String sha256) {
        return this.agentId.equals(agentId) && this.sha256.equals(sha256);
    }

    @Override
    public UUID getId() {
        return appealId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public UUID getAppealId() {
        return appealId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getSha256() {
        return sha256;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getReason() {
        return reason;
    }

    public AppealStatus getStatus() {
        return status;
    }

    public String getEvidenceKind() {
        return evidenceKind;
    }

    public String getThreat() {
        return threat;
    }

    public UUID getDecidedBy() {
        return decidedBy;
    }

    public String getDecisionNote() {
        return decisionNote;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
