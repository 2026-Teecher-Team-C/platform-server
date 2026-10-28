package com.teecherteamc.platform.ruleset.interfaces;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.teecherteamc.platform.ruleset.domain.Ruleset;
import java.time.Instant;

/** 룰셋 목록의 한 행. */
public record RulesetItem(
        int version,
        int ruleCount,
        long disabledRuleCount,
        @JsonProperty("isActive") boolean isActive,
        Instant createdAt,
        Instant activatedAt) {

    public static RulesetItem from(Ruleset ruleset, long disabledRuleCount) {
        return new RulesetItem(ruleset.getVersion(), ruleset.getRuleCount(), disabledRuleCount,
                ruleset.isActive(), ruleset.getCreatedAt(), ruleset.getActivatedAt());
    }
}
