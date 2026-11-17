package com.teecherteamc.platform.lookup.infrastructure;

import com.teecherteamc.platform.lookup.domain.CachedHashSourcePort;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class CachedHashSourcePortImpl implements CachedHashSourcePort {

    private final JdbcTemplate jdbcTemplate;

    CachedHashSourcePortImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<String> findAllCachedHashes() {
        // verdict/is_stale·활성/만료 무관 전체(거짓 양성은 안전) — 주기적 배치 작업이라 풀스캔이어도 괜찮다.
        return jdbcTemplate.queryForList(
                """
                SELECT sha256 FROM file_verdicts
                UNION
                SELECT hash_value FROM hash_blacklist WHERE hash_type = 'SHA256'
                UNION
                SELECT hash_value FROM hash_whitelist WHERE hash_type = 'SHA256'
                """,
                String.class);
    }
}
