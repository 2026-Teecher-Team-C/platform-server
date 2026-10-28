package com.teecherteamc.platform.ruleset.infrastructure;

import com.teecherteamc.platform.ruleset.domain.Ruleset;
import com.teecherteamc.platform.ruleset.domain.RulesetRepository;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/** domain의 RulesetRepository를 JPA로 구현한다. */
@Repository
class RulesetRepositoryImpl implements RulesetRepository {

    private final RulesetJpaRepository jpaRepository;

    RulesetRepositoryImpl(RulesetJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<Ruleset> findByVersion(int version) {
        return jpaRepository.findById(version);
    }

    @Override
    public Optional<Ruleset> findActive() {
        return jpaRepository.findFirstByActiveTrue();
    }

    @Override
    public Optional<Ruleset> findByVersionForUpdate(int version) {
        return jpaRepository.findByVersionForUpdate(version);
    }

    @Override
    public Optional<Integer> lockActiveVersionForShare() {
        return jpaRepository.lockActiveVersionForShare().stream().findFirst();
    }

    @Override
    public int findMaxVersion() {
        return jpaRepository.findMaxVersion();
    }

    @Override
    public Page<Ruleset> findListed(Pageable pageable) {
        return jpaRepository.findListed(pageable);
    }

    @Override
    public Ruleset save(Ruleset ruleset) {
        return jpaRepository.save(ruleset);
    }

    @Override
    public void deactivateAll() {
        jpaRepository.deactivateAll();
    }
}
