package com.teecherteamc.platform.ruleset.infrastructure;

import com.teecherteamc.platform.ruleset.domain.Ruleset;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** 룰셋용 DB 접근 도구. 같은 패키지의 RulesetRepositoryImpl만 쓴다. */
interface RulesetJpaRepository extends JpaRepository<Ruleset, Integer> {

    Optional<Ruleset> findFirstByActiveTrue();

    @Query("select coalesce(max(r.version), 0) from Ruleset r")
    int findMaxVersion();

    @Query(value = "select r from Ruleset r where r.version > 0 order by r.version desc",
            countQuery = "select count(r) from Ruleset r where r.version > 0")
    Page<Ruleset> findListed(Pageable pageable);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Ruleset r set r.active = false where r.active = true")
    int deactivateAll();
}
