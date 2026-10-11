package com.teecherteamc.platform.verdict.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

/** 룰 매칭 결과 1건. Analysis 애그리거트에 속한 자식 — Analysis를 저장할 때 cascade로 같이 저장된다. */
@Entity
@Table(name = "analysis_matches")
public class AnalysisMatch {

    @Id
    @Column(name = "match_id")
    private UUID matchId;

    @ManyToOne
    @JoinColumn(name = "analysis_id", nullable = false)
    private Analysis analysis;

    // yara_rules가 아직 안 채워져 있어서(룰셋 관리는 S4) 항상 null — 이름으로만 식별한다.
    @Column(name = "rule_id")
    private UUID ruleId;

    @Column(name = "rule_name", nullable = false, length = 128)
    private String ruleName;

    @Column(name = "severity", nullable = false, length = 16)
    private String severity;

    @Column(name = "matched_strings", columnDefinition = "text")
    private String matchedStrings;

    protected AnalysisMatch() {
        // JPA
    }

    AnalysisMatch(Analysis analysis, String ruleName, String severity, String matchedStringsJson) {
        this.matchId = UUID.randomUUID();
        this.analysis = analysis;
        this.ruleName = ruleName;
        this.severity = severity;
        this.matchedStrings = matchedStringsJson;
    }

    public UUID getMatchId() {
        return matchId;
    }

    public String getRuleName() {
        return ruleName;
    }

    public String getSeverity() {
        return severity;
    }

    public String getMatchedStrings() {
        return matchedStrings;
    }
}
