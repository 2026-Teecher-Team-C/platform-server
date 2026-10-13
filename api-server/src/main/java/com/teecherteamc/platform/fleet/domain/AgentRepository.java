package com.teecherteamc.platform.fleet.domain;

import java.util.Optional;
import java.util.UUID;

public interface AgentRepository {

    Agent save(Agent agent);

    Optional<Agent> findById(UUID agentId);

    Optional<Agent> findByAgentTokenHash(String agentTokenHash);
}
