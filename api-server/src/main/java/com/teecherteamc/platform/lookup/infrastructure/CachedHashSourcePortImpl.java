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
        // verdict/is_stale 무관 전체 — 주기적 배치 작업이라 풀스캔이어도 괜찮다.
        return jdbcTemplate.queryForList("SELECT sha256 FROM file_verdicts", String.class);
    }
}
