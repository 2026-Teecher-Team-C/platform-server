package com.teecherteamc.platform.hashlist.infrastructure;

import com.teecherteamc.platform.hashlist.domain.VerdictInvalidationPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** file_verdicts는 verdict 컨텍스트의 테이블이라 엔티티를 끌어오지 않고 UPDATE 한 줄만 한다. */
@Component
class VerdictInvalidationPortImpl implements VerdictInvalidationPort {

    private final JdbcTemplate jdbcTemplate;

    VerdictInvalidationPortImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void markStale(String sha256) {
        jdbcTemplate.update("UPDATE file_verdicts SET is_stale = true WHERE sha256 = ?", sha256);
    }
}
