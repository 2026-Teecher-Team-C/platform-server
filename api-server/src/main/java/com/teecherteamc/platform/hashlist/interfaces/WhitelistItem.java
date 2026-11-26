package com.teecherteamc.platform.hashlist.interfaces;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.teecherteamc.platform.hashlist.domain.WhitelistEntry;
import com.teecherteamc.platform.hashlist.domain.WhitelistRow;
import java.time.Instant;
import java.util.UUID;

/**
 * 화이트리스트 항목 한 건의 응답. 목록·등록·수정이 같은 모양을 쓴다.
 * 콘솔 console/src/lib/api/hashlists.ts의 WhitelistItem과 필드 이름이 같다.
 */
public record WhitelistItem(
        String hashType,            // 항상 "SHA256"
        String hashValue,
        String reason,
        String origin,              // APPEAL | RESTORED | PREREGISTERED (컬럼이 아니라 계산값)
        UUID originQuarantineId,    // 복원으로 들어온 경우만 값이 있다
        UUID originAppealId,        // 소명 승인으로 들어온 경우만 값이 있다
        AdminSummary addedBy,
        // 콘솔 필드 이름이 isActive라서 이름을 고정한다. (BlacklistItem과 같은 이유)
        @JsonProperty("isActive") boolean isActive,
        Instant expiresAt,          // null이면 무기한
        Instant createdAt
) {

    /** 등록자 요약. 콘솔의 AdminSummary({ adminId, username })와 같다. */
    public record AdminSummary(UUID adminId, String username) {
    }

    // 되살릴 때 다른 쪽 출처를 지우므로 둘 다 있는 행은 없다. 그래도 있으면 소명 출처를 앞세운다.
    static String origin(UUID originQuarantineId, UUID originAppealId) {
        if (originAppealId != null) {
            return "APPEAL";
        }
        return originQuarantineId == null ? "PREREGISTERED" : "RESTORED";
    }

    public static WhitelistItem from(WhitelistRow row) {
        return new WhitelistItem(
                row.hashType(),
                row.hashValue(),
                row.reason(),
                origin(row.originQuarantineId(), row.originAppealId()),
                row.originQuarantineId(),
                row.originAppealId(),
                new AdminSummary(row.addedById(), row.addedByUsername()),
                row.active(),
                row.expiresAt(),
                row.createdAt());
    }

    /** 엔티티에는 username이 없어서 호출한 쪽이 넘긴다. */
    public static WhitelistItem from(WhitelistEntry entry, String addedByUsername) {
        return new WhitelistItem(
                entry.getId().getHashType().name(),
                entry.getId().getHashValue(),
                entry.getReason(),
                origin(entry.getOriginQuarantineId(), entry.getOriginAppealId()),
                entry.getOriginQuarantineId(),
                entry.getOriginAppealId(),
                new AdminSummary(entry.getAddedBy(), addedByUsername),
                entry.isActive(),
                entry.getExpiresAt(),
                entry.getCreatedAt());
    }
}
