package com.teecherteamc.platform.appeal.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 관리자 콘솔용 소명 읽기 포트. Appeal 애그리거트를 거치지 않고 JdbcTemplate 조인으로 읽는다
 * ({@code quarantine.domain.ListQuarantineFilesPort}와 같은 이유).
 */
public interface ListAppealsPort {

    /** 최신순(created_at DESC). status가 null이면 전체. */
    List<AppealRow> list(String status, long offset, int limit);

    /** list()와 같은 조건의 전체 행 수. */
    long count(String status);

    Optional<AppealRow> findById(UUID appealId);
}
