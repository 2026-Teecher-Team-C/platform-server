package com.teecherteamc.platform.ruleset.interfaces;

import com.teecherteamc.platform.ruleset.domain.YaraRule;
import java.util.UUID;

/** 룰 목록의 한 행. */
public record YaraRuleItem(UUID ruleId, String ruleName, String severity, boolean enabled) {

    public static YaraRuleItem from(YaraRule rule) {
        return new YaraRuleItem(rule.getRuleId(), rule.getRuleName(), rule.getSeverity(), rule.isEnabled());
    }
}
