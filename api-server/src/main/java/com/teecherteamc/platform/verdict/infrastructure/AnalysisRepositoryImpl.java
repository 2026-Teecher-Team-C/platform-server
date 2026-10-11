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
        return jpaRepository.save(analysis);
    }
}
