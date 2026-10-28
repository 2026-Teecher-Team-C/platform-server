package com.teecherteamc.platform.ruleset.domain;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** 룰셋 저장소가 할 수 있어야 하는 일의 약속. 구현은 infrastructure의 RulesetRepositoryImpl. */
public interface RulesetRepository {

    Optional<Ruleset> findByVersion(int version);

    Optional<Ruleset> findActive();

    /** 버전으로 찾되 그 행에 쓰기 잠금(FOR UPDATE)을 건다. 트랜잭션 안에서만 쓴다. */
    Optional<Ruleset> findByVersionForUpdate(int version);

    /**
     * 활성 행에 공유 잠금(FOR SHARE)을 걸고 그 버전을 준다. 트랜잭션 안에서만 쓴다.
     * 활성화(활성 행 UPDATE)·활성 버전의 룰 토글(FOR UPDATE)과 서로 기다린다. 잠금을 기다리는 동안 활성화가
     * 커밋되면 PostgreSQL은 바뀐 행을 다시 평가해 빼므로 비어 있을 수 있다.
     */
    Optional<Integer> lockActiveVersionForShare();

    /** 가장 큰 버전 번호. v0이 항상 있으므로 비어 있을 수 없다. */
    int findMaxVersion();

    /** 목록용. v0을 빼고 버전 내림차순이다. */
    Page<Ruleset> findListed(Pageable pageable);

    Ruleset save(Ruleset ruleset);

    /** 모든 룰셋을 비활성으로 돌린다. bulk update이며 flush까지 한다. */
    void deactivateAll();
}
