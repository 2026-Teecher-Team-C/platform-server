package com.teecherteamc.platform.ruleset.domain;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 룰 저장소가 할 수 있어야 하는 일의 약속. 구현은 infrastructure의 YaraRuleRepositoryImpl. */
public interface YaraRuleRepository {

    /** 해당 버전의 룰을 이름 오름차순으로 준다. */
    List<YaraRule> findByVersion(int version);

    Optional<YaraRule> findByVersionAndId(int version, UUID ruleId);

    /** 버전별로 꺼진(enabled=false) 룰 수. 꺼진 룰이 없는 버전은 맵에 없다. */
    Map<Integer, Long> countDisabledByVersions(Collection<Integer> versions);

    /** 해당 버전에서 꺼진 룰의 이름들. */
    List<String> findDisabledNames(int version);

    void saveAll(List<YaraRule> rules);

    YaraRule save(YaraRule rule);
}
