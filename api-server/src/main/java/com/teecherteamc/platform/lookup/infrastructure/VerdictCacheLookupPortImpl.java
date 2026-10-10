package com.teecherteamc.platform.lookup.infrastructure;

import com.teecherteamc.platform.lookup.domain.CachedVerdict;
import com.teecherteamc.platform.lookup.domain.VerdictCacheLookupPort;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class VerdictCacheLookupPortImpl implements VerdictCacheLookupPort {

    private final JdbcTemplate jdbcTemplate;

    VerdictCacheLookupPortImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<CachedVerdict> find(String sha256) {
        List<CachedVerdict> rows = jdbcTemplate.query(
                "SELECT verdict, is_stale FROM file_verdicts WHERE sha256 = ?",
                (rs, rowNum) -> new CachedVerdict(rs.getString("verdict"), rs.getBoolean("is_stale")),
                sha256);
        return rows.stream().findFirst();
    }
}
