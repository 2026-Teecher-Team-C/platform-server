package com.teecherteamc.platform.ruleset.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** yara_rules 행. 룰셋 버전에 속한 룰 하나이며 Ruleset 애그리거트의 일부로 다룬다. created_at은 DB 기본값을 쓴다. */
@Entity
@Table(name = "yara_rules")
public class YaraRule {

    @Id
    @Column(name = "rule_id")
    private UUID ruleId;

    @Column(name = "rulesets_version", nullable = false)
    private int rulesetVersion;

    @Column(name = "rule_name", nullable = false, length = 128)
    private String ruleName;

    @Column(nullable = false, length = 16)
    private String severity;

    @Column(nullable = false)
    private boolean enabled;

    /** JPA 전용. 우리 코드는 of()로만 만든다. */
    protected YaraRule() {
    }

    public static YaraRule of(int version, String name, String severity) {
        YaraRule rule = new YaraRule();
        rule.ruleId = UUID.randomUUID();
        rule.rulesetVersion = version;
        rule.ruleName = name;
        rule.severity = severity;
        rule.enabled = true;
        return rule;
    }

    public void changeEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public UUID getRuleId() {
        return ruleId;
    }

    public int getRulesetVersion() {
        return rulesetVersion;
    }

    public String getRuleName() {
        return ruleName;
    }

    public String getSeverity() {
        return severity;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
