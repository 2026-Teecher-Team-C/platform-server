package com.teecherteamc.platform.ruleset.domain;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** 룰셋 저장소가 할 수 있어야 하는 일의 약속. 구현은 infrastructure의 RulesetRepositoryImpl. */
public interface RulesetRepository {

    Optional<Ruleset> findByVersion(int version);

    Optional<Ruleset> findActive();

    /** 가장 큰 버전 번호. v0이 항상 있으므로 비어 있을 수 없다. */
    int findMaxVersion();

    /** 목록용. v0을 빼고 버전 내림차순이다. */
    Page<Ruleset> findListed(Pageable pageable);

    Ruleset save(Ruleset ruleset);

    /** 모든 룰셋을 비활성으로 돌린다. bulk update이며 flush까지 한다. */
    void deactivateAll();
}
