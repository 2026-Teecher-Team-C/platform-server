package com.teecherteamc.platform.verdict.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * 해시 단위 판정 캐시 애그리거트 루트. 테이블: file_verdicts.
 *
 * <p>SubmitFile이 이 애그리거트에 처음으로 쓴다(CheckHash는 JdbcTemplate로 직접 읽기만 했다).
 * 이미 있는 해시를 재검사(S4 룰셋 재검사 등)하면 recordAnalysis()로 갱신, 처음 보는 해시면
 * create()로 새로 만든다 — eventId처럼 ID를 직접 할당하는 애그리거트라 isNew를 직접 관리해야
 * Spring Data가 save()를 INSERT/UPDATE 중 맞는 쪽으로 보낸다.
 */
@Entity
@Table(name = "file_verdicts")
public class FileVerdict implements Persistable<String> {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "sha256", length = 64)
    private String sha256;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "detected_file_type", length = 32)
    private String detectedFileType;

    @Column(name = "verdict", nullable = false, length = 16)
    private String verdict;

    @Column(name = "verdict_source", nullable = false, length = 16)
    private String verdictSource;

    @Column(name = "rulesets_version")
    private Integer rulesetsVersion;

    @Column(name = "is_stale", nullable = false)
    private boolean stale;

    @Column(name = "analysis_count", nullable = false)
    private int analysisCount;

    @Column(name = "hit_count", nullable = false)
    private long hitCount;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_verdict_at", nullable = false)
    private Instant lastVerdictAt;

    @Transient
    private boolean isNew;

    protected FileVerdict() {
        // JPA가 기존 행을 읽어서 만들 때 쓴다 — isNew는 기본값 false로 남는다(이미 존재하는 행이므로).
    }

    private FileVerdict(
            String sha256,
            Long fileSize,
            String detectedFileType,
            String verdict,
            String verdictSource,
            Integer rulesetsVersion,
            Instant now) {
        this.sha256 = sha256;
        this.fileSize = fileSize;
        this.detectedFileType = detectedFileType;
        this.verdict = verdict;
        this.verdictSource = verdictSource;
        this.rulesetsVersion = rulesetsVersion;
        this.stale = false;
        this.analysisCount = 1;
        this.hitCount = 0;
        this.firstSeenAt = now;
        this.lastVerdictAt = now;
        this.isNew = true;
    }

    public static FileVerdict create(
            String sha256,
            Long fileSize,
            String detectedFileType,
            String verdict,
            String verdictSource,
            Integer rulesetsVersion,
            Instant now) {
        return new FileVerdict(sha256, fileSize, detectedFileType, verdict, verdictSource, rulesetsVersion, now);
    }

    /** 이미 있는 해시를 다시 분석한 결과로 갱신한다. is_stale을 해제하고 분석 횟수를 늘린다. */
    public void recordAnalysis(
            String verdict, String verdictSource, Integer rulesetsVersion, String detectedFileType, Instant now) {
        this.verdict = verdict;
        this.verdictSource = verdictSource;
        this.rulesetsVersion = rulesetsVersion;
        this.detectedFileType = detectedFileType;
        this.stale = false;
        this.analysisCount = this.analysisCount + 1;
        this.lastVerdictAt = now;
    }

    /** 이 판정을 더 이상 캐시로 쓰지 않게 한다. 다음 조회 때 재검사되고 recordAnalysis()가 stale을 해제한다. */
    public void markStale() {
        this.stale = true;
    }

    @Override
    public String getId() {
        return sha256;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public String getSha256() {
        return sha256;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public String getDetectedFileType() {
        return detectedFileType;
    }

    public String getVerdict() {
        return verdict;
    }

    public String getVerdictSource() {
        return verdictSource;
    }

    public Integer getRulesetsVersion() {
        return rulesetsVersion;
    }

    public boolean isStale() {
        return stale;
    }

    public int getAnalysisCount() {
        return analysisCount;
    }

    public long getHitCount() {
        return hitCount;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public Instant getLastVerdictAt() {
        return lastVerdictAt;
    }
}
