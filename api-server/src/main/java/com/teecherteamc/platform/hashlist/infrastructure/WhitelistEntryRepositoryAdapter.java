package com.teecherteamc.platform.hashlist.infrastructure;

import com.teecherteamc.platform.hashlist.domain.HashKey;
import com.teecherteamc.platform.hashlist.domain.WhitelistEntry;
import com.teecherteamc.platform.hashlist.domain.WhitelistEntryRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * domain의 WhitelistEntryRepository(약속)를 JPA로 구현한다.
 * 실제 DB 작업은 WhitelistEntryJpaRepository에 맡기고, 여기서는 연결만 한다.
 */
@Repository
class WhitelistEntryRepositoryAdapter implements WhitelistEntryRepository {

    private final WhitelistEntryJpaRepository jpaRepository;

    WhitelistEntryRepositoryAdapter(WhitelistEntryJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public WhitelistEntry save(WhitelistEntry entry) {
        return jpaRepository.save(entry);
    }

    @Override
    public Optional<WhitelistEntry> findByKey(HashKey key) {
        return jpaRepository.findById(key);
    }

    @Override
    public boolean existsEffective(HashKey key, Instant now) {
        return jpaRepository.existsEffective(key, now);
    }
}