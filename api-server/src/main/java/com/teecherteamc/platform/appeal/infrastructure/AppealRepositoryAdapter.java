package com.teecherteamc.platform.appeal.infrastructure;

import com.teecherteamc.platform.appeal.domain.Appeal;
import com.teecherteamc.platform.appeal.domain.AppealRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** domain의 AppealRepository(약속)를 JPA로 구현한다. */
@Repository
class AppealRepositoryAdapter implements AppealRepository {

    private final AppealJpaRepository jpaRepository;

    AppealRepositoryAdapter(AppealJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Appeal saveNew(Appeal appeal) {
        // flush까지 해야 유니크 위반이 유스케이스 안에서 잡힌다(커밋 시점까지 미루지 않는다)
        return jpaRepository.saveAndFlush(appeal);
    }

    @Override
    public Optional<Appeal> findByEventId(UUID eventId) {
        return jpaRepository.findByEventId(eventId);
    }
}
