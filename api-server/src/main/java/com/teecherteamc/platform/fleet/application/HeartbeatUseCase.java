package com.teecherteamc.platform.fleet.application;

import com.teecherteamc.platform.fleet.domain.Agent;
import com.teecherteamc.platform.fleet.domain.AgentNotFoundException;
import com.teecherteamc.platform.fleet.domain.AgentRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HeartbeatUseCase {

    private final AgentRepository agentRepository;

    public HeartbeatUseCase(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    @Transactional
    public Instant heartbeat(HeartbeatCommand command) {
        Agent agent = agentRepository.findById(command.agentId()).orElseThrow(AgentNotFoundException::new);
        Instant now = Instant.now();
        agent.recordHeartbeat(command.agentVersion(), now);
        agentRepository.save(agent);
        return now;
    }
}
