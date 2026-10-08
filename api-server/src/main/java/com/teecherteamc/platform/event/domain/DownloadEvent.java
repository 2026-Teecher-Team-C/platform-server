package com.teecherteamc.platform.event.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * 다운로드 이벤트 애그리거트 루트. 테이블: download_events.
 *
 * <p>agentId/sha256은 fleet/verdict 컨텍스트 소유 애그리거트라 연관관계를 걸지 않고 스칼라로만 보관한다.
 * decision/decisionSource/pipelineStatus는 DB에서 Postgres enum이 아니라 CHECK 제약이고, 유효값의
 * 근거는 proto enum이라 평범한 String으로 저장한다 — 매핑은 interfaces 레이어에서 명시적으로 한다.
 *
 * <p>eventId는 에이전트가 미리 만들어서 보내는 값이라(@GeneratedValue 없음), Persistable을 구현하지
 * 않으면 Spring Data가 "이미 있는 엔티티"로 오판해 save()가 INSERT 대신 UPDATE(merge)를 해버리고,
 * 중복 event_id가 에러 없이 조용히 덮어써진다. 이 애그리거트는 갱신 유스케이스가 없어 항상 새 행이다.
 */
@Entity
@Table(name = "download_events")
public class DownloadEvent implements Persistable<UUID> {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "sha256", length = 64)
    private String sha256;

    @Column(name = "request_host", nullable = false)
    private String requestHost;

    @Column(name = "url", columnDefinition = "text", nullable = false)
    private String url;

    @Column(name = "filename", length = 512)
    private String filename;

    @Column(name = "mime_type")
    private String mimeType;

    @Column(name = "content_disposition", columnDefinition = "text")
    private String contentDisposition;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "pipeline_status", nullable = false, length = 16)
    private String pipelineStatus;

    @Column(name = "decision", length = 16)
    private String decision;

    @Column(name = "decision_source", length = 16)
    private String decisionSource;

    @Column(name = "cache_hit")
    private Boolean cacheHit;

    @Column(name = "bytes_uploaded", nullable = false)
    private long bytesUploaded;

    @Column(name = "held_at", nullable = false)
    private Instant heldAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "hold_duration_ms")
    private Integer holdDurationMs;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected DownloadEvent() {
        // JPA
    }

    private DownloadEvent(
            UUID eventId,
            UUID agentId,
            String sha256,
            String requestHost,
            String url,
            String filename,
            String mimeType,
            String contentDisposition,
            Long fileSize,
            String pipelineStatus,
            String decision,
            String decisionSource,
            Boolean cacheHit,
            long bytesUploaded,
            Instant heldAt,
            Instant decidedAt,
            Integer holdDurationMs) {
        this.eventId = eventId;
        this.agentId = agentId;
        this.sha256 = sha256;
        this.requestHost = requestHost;
        this.url = url;
        this.filename = filename;
        this.mimeType = mimeType;
        this.contentDisposition = contentDisposition;
        this.fileSize = fileSize;
        this.pipelineStatus = pipelineStatus;
        this.decision = decision;
        this.decisionSource = decisionSource;
        this.cacheHit = cacheHit;
        this.bytesUploaded = bytesUploaded;
        this.heldAt = heldAt;
        this.decidedAt = decidedAt;
        this.holdDurationMs = holdDurationMs;
    }

    /** ReportEvent는 보류가 끝난 뒤 최종 결정과 함께 1회만 호출되므로 pipelineStatus는 항상 COMPLETED로 기록한다. */
    public static DownloadEvent record(
            UUID eventId,
            UUID agentId,
            String sha256,
            String requestHost,
            String url,
            String filename,
            String mimeType,
            String contentDisposition,
            Long fileSize,
            String decision,
            String decisionSource,
            Boolean cacheHit,
            long bytesUploaded,
            Instant heldAt,
            Instant decidedAt,
            Integer holdDurationMs) {
        return new DownloadEvent(
                eventId,
                agentId,
                sha256,
                requestHost,
                url,
                filename,
                mimeType,
                contentDisposition,
                fileSize,
                "COMPLETED",
                decision,
                decisionSource,
                cacheHit,
                bytesUploaded,
                heldAt,
                decidedAt,
                holdDurationMs);
    }

    @Override
    public UUID getId() {
        return eventId;
    }

    @Override
    public boolean isNew() {
        return true;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getSha256() {
        return sha256;
    }

    public String getRequestHost() {
        return requestHost;
    }

    public String getUrl() {
        return url;
    }

    public String getFilename() {
        return filename;
    }

    public String getMimeType() {
        return mimeType;
    }

    public String getContentDisposition() {
        return contentDisposition;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public String getPipelineStatus() {
        return pipelineStatus;
    }

    public String getDecision() {
        return decision;
    }

    public String getDecisionSource() {
        return decisionSource;
    }

    public Boolean getCacheHit() {
        return cacheHit;
    }

    public long getBytesUploaded() {
        return bytesUploaded;
    }

    public Instant getHeldAt() {
        return heldAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public Integer getHoldDurationMs() {
        return holdDurationMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
