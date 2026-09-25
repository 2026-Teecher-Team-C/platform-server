package com.teecherteamc.platform.event.infrastructure;

import com.teecherteamc.platform.event.domain.FileVerdictExistsPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * verdict 컨텍스트 소유 테이블(file_verdicts)을 읽기 전용으로 확인만 하는 어댑터.
 * FileVerdict 애그리거트를 이 컨텍스트에 새로 들이지 않기 위해 한 줄짜리 존재 쿼리로 최소화한다.
 */
@Component
class FileVerdictExistsPortImpl implements FileVerdictExistsPort {

    private final JdbcTemplate jdbcTemplate;

    FileVerdictExistsPortImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean exists(String sha256) {
        Boolean result = jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM file_verdicts WHERE sha256 = ?)", Boolean.class, sha256);
        return Boolean.TRUE.equals(result);
    }
}
