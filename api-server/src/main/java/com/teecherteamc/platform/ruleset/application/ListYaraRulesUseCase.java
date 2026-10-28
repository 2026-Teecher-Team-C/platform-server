package com.teecherteamc.platform.ruleset.application;

import com.teecherteamc.platform.ruleset.domain.RulesetRepository;
import com.teecherteamc.platform.ruleset.domain.YaraRule;
import com.teecherteamc.platform.ruleset.domain.YaraRuleRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 한 룰셋 버전의 룰 목록 조회. 없는 버전과 v0은 RulesetNotFoundException이다. */
@Service
public class ListYaraRulesUseCase {

    private final RulesetRepository rulesetRepository;
    private final YaraRuleRepository yaraRuleRepository;

    public ListYaraRulesUseCase(RulesetRepository rulesetRepository, YaraRuleRepository yaraRuleRepository) {
        this.rulesetRepository = rulesetRepository;
        this.yaraRuleRepository = yaraRuleRepository;
    }

    @Transactional(readOnly = true)
    public List<YaraRule> list(int version) {
        if (version <= 0 || rulesetRepository.findByVersion(version).isEmpty()) {
            throw RulesetNotFoundException.ofVersion(version);
        }
        return yaraRuleRepository.findByVersion(version);
    }

    /** 한 버전에서 꺼진 룰 수. */
    @Transactional(readOnly = true)
    public long countDisabled(int version) {
        return yaraRuleRepository.countDisabledByVersions(List.of(version)).getOrDefault(version, 0L);
    }
}
