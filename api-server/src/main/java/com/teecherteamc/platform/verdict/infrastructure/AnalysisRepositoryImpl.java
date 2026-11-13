package com.teecherteamc.platform.verdict.infrastructure;

import com.teecherteamc.platform.verdict.domain.Analysis;
import com.teecherteamc.platform.verdict.domain.AnalysisRepository;
import org.springframework.stereotype.Repository;

@Repository
class AnalysisRepositoryImpl implements AnalysisRepository {

    private final AnalysisJpaRepository jpaRepository;

    AnalysisRepositoryImpl(AnalysisJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Analysis save(Analysis analysis) {
        // 뒤따르는 JDBC 근거가 analysis_id FK를 참조하므로 같은 트랜잭션에서 먼저 반영한다.
        return jpaRepository.saveAndFlush(analysis);
    }
}
