package com.teecherteamc.platform.ruleset.infrastructure;

import com.teecherteamc.platform.ruleset.domain.YaraRule;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 룰용 DB 접근 도구. 같은 패키지의 YaraRuleRepositoryImpl만 쓴다. */
interface YaraRuleJpaRepository extends JpaRepository<YaraRule, UUID> {

    List<YaraRule> findByRulesetVersionOrderByRuleNameAsc(int version);

    Optional<YaraRule> findByRulesetVersionAndRuleId(int version, UUID ruleId);

    @Query("select r.rulesetVersion, count(r) from YaraRule r "
            + "where r.rulesetVersion in :versions and r.enabled = false group by r.rulesetVersion")
    List<Object[]> countDisabledByVersions(@Param("versions") Collection<Integer> versions);

    @Query("select r.ruleName from YaraRule r where r.rulesetVersion = :version and r.enabled = false "
            + "order by r.ruleName")
    List<String> findDisabledNames(@Param("version") int version);
}
