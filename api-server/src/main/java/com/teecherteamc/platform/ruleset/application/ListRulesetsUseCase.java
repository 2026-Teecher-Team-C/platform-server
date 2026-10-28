package com.teecherteamc.platform.ruleset.application;

import com.teecherteamc.platform.ruleset.domain.Ruleset;
import com.teecherteamc.platform.ruleset.domain.RulesetRepository;
import com.teecherteamc.platform.ruleset.domain.YaraRuleRepository;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 룰셋 버전 목록 조회. v0을 뺀 최신순 한 페이지와, 그 페이지 버전들의 꺼진 룰 수를 함께 준다. */
@Service
public class ListRulesetsUseCase {

    private final RulesetRepository rulesetRepository;
    private final YaraRuleRepository yaraRuleRepository;

    public ListRulesetsUseCase(RulesetRepository rulesetRepository, YaraRuleRepository yaraRuleRepository) {
        this.rulesetRepository = rulesetRepository;
        this.yaraRuleRepository = yaraRuleRepository;
    }

    public record Result(Page<Ruleset> page, Map<Integer, Long> disabledCounts) {
        public long disabledCount(int version) {
            return disabledCounts.getOrDefault(version, 0L);
        }
    }

    @Transactional(readOnly = true)
    public Result list(int page, int size) {
        Page<Ruleset> rulesets = rulesetRepository.findListed(PageRequest.of(page, size));
        Map<Integer, Long> disabled = yaraRuleRepository.countDisabledByVersions(
                rulesets.getContent().stream().map(Ruleset::getVersion).toList());
        return new Result(rulesets, disabled);
    }
}
