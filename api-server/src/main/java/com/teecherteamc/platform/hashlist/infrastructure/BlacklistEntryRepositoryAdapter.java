package com.teecherteamc.platform.hashlist.infrastructure;

import com.teecherteamc.platform.hashlist.domain.BlacklistEntry;
import com.teecherteamc.platform.hashlist.domain.BlacklistEntryRepository;
import com.teecherteamc.platform.hashlist.domain.HashKey;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * domain의 BlacklistEntryRepository(약속)를 JPA로 구현한다.
 * 실제 DB 작업은 BlacklistEntryJpaRepository에 맡기고, 여기서는 연결만 한다.
 */
@Repository
class BlacklistEntryRepositoryAdapter implements BlacklistEntryRepository {

    private final BlacklistEntryJpaRepository jpaRepository;

    BlacklistEntryRepositoryAdapter(BlacklistEntryJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public BlacklistEntry save(BlacklistEntry entry) {
        return jpaRepository.save(entry);
    }

    @Override
    public Optional<BlacklistEntry> findByKey(HashKey key) {
        return jpaRepository.findById(key);
    }

    @Override
    public boolean existsActive(HashKey key) {
        return jpaRepository.existsActive(key);
    }
}