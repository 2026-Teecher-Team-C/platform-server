package com.teecherteamc.platform.event.infrastructure;

import com.teecherteamc.platform.event.domain.FindAgentHostnamePort;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class FindAgentHostnamePortImpl implements FindAgentHostnamePort {

    private static final String SQL =
            """
            SELECT d.hostname
            FROM agents a
            JOIN devices d ON d.device_id = a.device_id
            WHERE a.agent_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    FindAgentHostnamePortImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<String> findHostname(UUID agentId) {
        return jdbcTemplate.queryForList(SQL, String.class, agentId).stream()
                .filter(Objects::nonNull)
                .findFirst();
    }
}
