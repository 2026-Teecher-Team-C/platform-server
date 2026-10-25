package com.teecherteamc.platform.lookup.infrastructure;

import com.teecherteamc.platform.lookup.domain.WhitelistLookupPort;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class WhitelistLookupPortImpl implements WhitelistLookupPort {

    private final JdbcTemplate jdbcTemplate;

    WhitelistLookupPortImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean isEffective(String sha256, Instant now) {
        Boolean effective = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1 FROM hash_whitelist
                    WHERE hash_type = 'SHA256' AND hash_value = ? AND is_active = true
                      AND (expires_at IS NULL OR expires_at > ?)
                )
                """,
                Boolean.class,
                sha256,
                Timestamp.from(now));
        return Boolean.TRUE.equals(effective);
    }
}
