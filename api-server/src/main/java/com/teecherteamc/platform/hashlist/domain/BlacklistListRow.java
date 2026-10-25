package com.teecherteamc.platform.hashlist.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * hash_blacklist + admin_users.username 조인 결과 1행. 목록 전용 읽기 모델 — JPA 엔티티가 아니다.
 * 등록자(admin_users)는 admin 컨텍스트의 데이터라서 BlacklistEntry에는 username을 두지 않고, 목록 조회만 이 행으로 읽는다.
 * addedBy가 null이면(일괄 등록 등) addedByUsername도 null이다.
 */
public record BlacklistListRow(
        String hashType,
        String hashValue,
        String reason,
        String severity,
        String source,
        UUID addedBy,
        String addedByUsername,
        boolean active,
        Instant createdAt,
        Instant deactivatedAt) {
}