package com.teecherteamc.platform.fleet.infrastructure;

import com.teecherteamc.platform.fleet.domain.EnrollmentToken;
import com.teecherteamc.platform.fleet.domain.EnrollmentTokenRepository;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
class EnrollmentTokenRepositoryImpl implements EnrollmentTokenRepository {

    private final EnrollmentTokenJpaRepository jpaRepository;

    EnrollmentTokenRepositoryImpl(EnrollmentTokenJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public EnrollmentToken save(EnrollmentToken enrollmentToken) {
        // saveAndFlush를 쓰는 이유: @Version 체크(UPDATE ... WHERE version=?)는 기본적으로 커밋
        // 시점까지 미뤄진다 — 그러면 RegisterAgentUseCase의 try/catch가 이미 메서드를 빠져나간
        // 뒤라 OptimisticLockingFailureException을 못 잡는다. 즉시 flush해서 호출 지점에서 바로
        // 터지게 한다.
        return jpaRepository.saveAndFlush(enrollmentToken);
    }

    @Override
    public Optional<EnrollmentToken> findByEnrollmentTokenHash(String enrollmentTokenHash) {
        return jpaRepository.findByEnrollmentTokenHash(enrollmentTokenHash);
    }
}
