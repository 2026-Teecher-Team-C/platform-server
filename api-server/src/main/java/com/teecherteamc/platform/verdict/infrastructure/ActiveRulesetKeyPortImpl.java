package com.teecherteamc.platform.verdict.infrastructure;

import com.teecherteamc.platform.ruleset.application.ActiveRulesetService;
import com.teecherteamc.platform.verdict.domain.ActiveRulesetKeyPort;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** 룰셋 컨텍스트의 ActiveRulesetService에 위임한다. 키 계산은 RulesetSnapshot.keyOf 하나만 쓴다. */
@Component
class ActiveRulesetKeyPortImpl implements ActiveRulesetKeyPort {

    private final ActiveRulesetService activeRulesetService;

    ActiveRulesetKeyPortImpl(ActiveRulesetService activeRulesetService) {
        this.activeRulesetService = activeRulesetService;
    }

    @Override
    public Optional<String> lockActiveKey() {
        return activeRulesetService.lockActiveKey();
    }
}
