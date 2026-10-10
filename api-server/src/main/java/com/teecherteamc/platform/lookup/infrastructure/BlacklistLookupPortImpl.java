package com.teecherteamc.platform.lookup.infrastructure;

import com.teecherteamc.platform.lookup.domain.BlacklistLookupPort;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class BlacklistLookupPortImpl implements BlacklistLookupPort {

    private final JdbcTemplate jdbcTemplate;

    BlacklistLookupPortImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<String> findActiveReason(String sha256) {
        List<String> reasons = jdbcTemplate.queryForList(
                """
                SELECT reason FROM hash_blacklist
                WHERE hash_type = 'SHA256' AND hash_value = ? AND is_active = true
                """,
                String.class,
                sha256);
        return reasons.stream().findFirst();
    }
}
