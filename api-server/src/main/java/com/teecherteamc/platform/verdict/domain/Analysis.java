package com.teecherteamc.platform.verdict.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * 탐지 엔진 실행 1회의 기록. 테이블: analyses(+ analysis_matches).
 *
 * <p>analysisId는 우리가 직접 발급하는 UUID라(에이전트가 아니라 서버가 생성), DownloadEvent와 같은
 * 이유로 Persistable이 필요하다 — 이 애그리거트는 갱신 유스케이스가 없어 항상 새 행이다.
 *
 * <p>sha256 -&gt; file_verdicts FK가 있어서, 같은 트랜잭션 안에서 FileVerdict를 먼저 저장한 뒤에
 * 저장해야 한다(순서 중요).
 */
@Entity
@Table(name = "analyses")
public class Analysis implements Persistable<UUID> {

    @Id
    @Column(name = "analysis_id")
    private UUID analysisId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "sha256", length = 64, nullable = false)
    private String sha256;

    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "rulesets_version", nullable = false)
    private Integer rulesetsVersion;

    @Column(name = "engine_version", nullable = false, length = 32)
    private String engineVersion;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "verdict", nullable = false, length = 16)
    private String verdict;

    @Column(name = "detected_file_type", length = 32)
    private String detectedFileType;

    @Column(name = "pe_parsed", nullable = false)
    private boolean peParsed;

    @Column(name = "max_section_entropy", precision = 4, scale = 3)
    private BigDecimal maxSectionEntropy;

    @Column(name = "duration_ms", nullable = false)
    private int durationMs;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "analyzed_at", insertable = false, updatable = false)
    private Instant analyzedAt;

    @OneToMany(mappedBy = "analysis", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<AnalysisMatch> matches = new ArrayList<>();

    protected Analysis() {
        // JPA
    }

    private Analysis(
            UUID analysisId,
            String sha256,
            UUID eventId,
            Integer rulesetsVersion,
            String engineVersion,
            String status,
            String verdict,
            String detectedFileType,
            boolean peParsed,
            BigDecimal maxSectionEntropy,
            int durationMs,
            String errorMessage) {
        this.analysisId = analysisId;
        this.sha256 = sha256;
        this.eventId = eventId;
        this.rulesetsVersion = rulesetsVersion;
        this.engineVersion = engineVersion;
        this.status = status;
        this.verdict = verdict;
        this.detectedFileType = detectedFileType;
        this.peParsed = peParsed;
        this.maxSectionEntropy = maxSectionEntropy;
        this.durationMs = durationMs;
        this.errorMessage = errorMessage;
    }

    public static Analysis record(
            String sha256,
            UUID eventId,
            Integer rulesetsVersion,
            String engineVersion,
            String status,
            String verdict,
            String detectedFileType,
            boolean peParsed,
            BigDecimal maxSectionEntropy,
            int durationMs,
            String errorMessage) {
        return new Analysis(
                UUID.randomUUID(),
                sha256,
                eventId,
                rulesetsVersion,
                engineVersion,
                status,
                verdict,
                detectedFileType,
                peParsed,
                maxSectionEntropy,
                durationMs,
                errorMessage);
    }

    public void addMatch(String ruleName, String severity, String matchedStringsJson) {
        matches.add(new AnalysisMatch(this, ruleName, severity, matchedStringsJson));
    }

    @Override
    public UUID getId() {
        return analysisId;
    }

    @Override
    public boolean isNew() {
        return true;
    }

    public UUID getAnalysisId() {
        return analysisId;
    }

    public String getSha256() {
        return sha256;
    }

    public String getStatus() {
        return status;
    }

    public String getVerdict() {
        return verdict;
    }

    public List<AnalysisMatch> getMatches() {
        return matches;
    }
}
