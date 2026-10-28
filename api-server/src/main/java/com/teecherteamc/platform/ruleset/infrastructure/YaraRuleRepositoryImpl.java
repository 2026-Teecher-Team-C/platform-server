package com.teecherteamc.platform.ruleset.infrastructure;

import com.teecherteamc.platform.ruleset.domain.YaraRule;
import com.teecherteamc.platform.ruleset.domain.YaraRuleRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** domain의 YaraRuleRepository를 JPA로 구현한다. */
@Repository
class YaraRuleRepositoryImpl implements YaraRuleRepository {

    private final YaraRuleJpaRepository jpaRepository;

    YaraRuleRepositoryImpl(YaraRuleJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public List<YaraRule> findByVersion(int version) {
        return jpaRepository.findByRulesetVersionOrderByRuleNameAsc(version);
    }

    @Override
    public Optional<YaraRule> findByVersionAndId(int version, UUID ruleId) {
        return jpaRepository.findByRulesetVersionAndRuleId(version, ruleId);
    }

    @Override
    public Map<Integer, Long> countDisabledByVersions(Collection<Integer> versions) {
        Map<Integer, Long> counts = new HashMap<>();
        if (versions.isEmpty()) {
            return counts;
        }
        for (Object[] row : jpaRepository.countDisabledByVersions(versions)) {
            counts.put((Integer) row[0], (Long) row[1]);
        }
        return counts;
    }

    @Override
    public List<String> findDisabledNames(int version) {
        return jpaRepository.findDisabledNames(version);
    }

    @Override
    public void saveAll(List<YaraRule> rules) {
        jpaRepository.saveAll(rules);
    }

    @Override
    public YaraRule save(YaraRule rule) {
        return jpaRepository.save(rule);
    }
}
