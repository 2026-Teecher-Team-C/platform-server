package com.teecherteamc.platform.fleet.infrastructure;

import com.teecherteamc.platform.fleet.domain.Agent;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AgentJpaRepository extends JpaRepository<Agent, UUID> {

    Optional<Agent> findByAgentTokenHash(String agentTokenHash);
}
