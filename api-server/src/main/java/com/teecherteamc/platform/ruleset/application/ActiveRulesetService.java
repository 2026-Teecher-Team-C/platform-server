package com.teecherteamc.platform.ruleset.application;

import com.teecherteamc.platform.ruleset.domain.EngineRulesetPort;
import com.teecherteamc.platform.ruleset.domain.Ruleset;
import com.teecherteamc.platform.ruleset.domain.RulesetRepository;
import com.teecherteamc.platform.ruleset.domain.RulesetSnapshot;
import com.teecherteamc.platform.ruleset.domain.YaraRuleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지금 활성인 룰셋을 엔진에 맞추는 일. 커밋 직후(AFTER_COMMIT)에도 부를 수 있도록 읽기를 새 트랜잭션에서 한다.
 */
@Service
public class ActiveRulesetService {

    private final RulesetRepository rulesetRepository;
    private final YaraRuleRepository yaraRuleRepository;
    private final EngineRulesetPort engine;

    public ActiveRulesetService(RulesetRepository rulesetRepository, YaraRuleRepository yaraRuleRepository,
                                EngineRulesetPort engine) {
        this.rulesetRepository = rulesetRepository;
        this.yaraRuleRepository = yaraRuleRepository;
        this.engine = engine;
    }

    /** 활성 버전의 원문과 꺼진 룰 이름. 활성 버전이 없으면 IllegalStateException. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public RulesetSnapshot activeSnapshot() {
        Ruleset active = rulesetRepository.findActive()
                .orElseThrow(() -> new IllegalStateException("활성 룰셋이 없습니다."));
        return new RulesetSnapshot(active.getVersion(), active.getSource(),
                yaraRuleRepository.findDisabledNames(active.getVersion()));
    }

    /** 활성 스냅샷을 엔진에 적재한다. 엔진에 닿지 못하면 예외가 그대로 올라간다. */
    public void pushActive() {
        engine.load(activeSnapshot());
    }
}
