package com.teecherteamc.platform.ruleset.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * yara_rulesets 애그리거트 루트. 룰셋 한 버전이며, 활성 버전은 항상 1개다.
 * v0은 "룰 없음" 기본 행이고 목록에는 나오지 않는다.
 */
@Entity
@Table(name = "yara_rulesets")
public class Ruleset {

    @Id
    @Column(name = "rulesets_version")
    private int version;

    @Column(name = "rule_count", nullable = false)
    private int ruleCount;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "activated_by")
    private UUID activatedBy;

    /** 업로드된 .yar 원문. v0은 없다. */
    @Column(columnDefinition = "text")
    private String source;

    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA 전용. 우리 코드는 upload()로만 만든다. */
    protected Ruleset() {
    }

    /** 새 버전을 대기(비활성) 상태로 만든다. */
    public static Ruleset upload(int version, String source, int ruleCount, UUID uploadedBy, Instant now) {
        Ruleset ruleset = new Ruleset();
        ruleset.version = version;
        ruleset.source = source;
        ruleset.ruleCount = ruleCount;
        ruleset.uploadedBy = uploadedBy;
        ruleset.active = false;
        ruleset.createdAt = now;
        return ruleset;
    }

    public void activate(UUID adminId, Instant now) {
        this.active = true;
        this.activatedBy = adminId;
        this.activatedAt = now;
    }

    /** 비활성으로 돌린다. 활성화 이력(activatedAt/By)은 마지막 값을 남긴다. */
    public void deactivate() {
        this.active = false;
    }

    public int getVersion() {
        return version;
    }

    public int getRuleCount() {
        return ruleCount;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public UUID getActivatedBy() {
        return activatedBy;
    }

    public String getSource() {
        return source;
    }

    public UUID getUploadedBy() {
        return uploadedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
