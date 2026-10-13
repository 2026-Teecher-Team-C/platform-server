package com.teecherteamc.platform.fleet.infrastructure;

import com.teecherteamc.platform.fleet.domain.Agent;
import com.teecherteamc.platform.fleet.domain.AgentRepository;
import org.springframework.stereotype.Repository;

@Repository
class AgentRepositoryImpl implements AgentRepository {

    private final AgentJpaRepository jpaRepository;

    AgentRepositoryImpl(AgentJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Agent save(Agent agent) {
        return jpaRepository.save(agent);
    }
}
