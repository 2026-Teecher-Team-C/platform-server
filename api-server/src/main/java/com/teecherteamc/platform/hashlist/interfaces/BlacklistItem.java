package com.teecherteamc.platform.hashlist.interfaces;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.teecherteamc.platform.hashlist.domain.BlacklistEntry;
import com.teecherteamc.platform.hashlist.domain.BlacklistListRow;
import java.time.Instant;
import java.util.UUID;

/**
 * 블랙리스트 항목 한 건의 응답. 콘솔 console/src/lib/api/hashlists.ts의 BlacklistItem과 필드 이름이 같다.
 * 엔티티를 그대로 내리지 않고 이 클래스로 바꿔서 내려준다.
 *
 * updatedAt은 콘솔 타입에서 선택(?) 필드인데 hash_blacklist에 그 컬럼이 없어서 내리지 않는다.
 */
public record BlacklistItem(
        String hashType,            // 현재는 항상 "SHA256"
        String hashValue,
        String reason,
        String severity,            // LOW | MEDIUM | HIGH | CRITICAL
        String source,              // MANUAL | IMPORT
        AdminSummary addedBy,       // 사람이 등록하지 않았으면 null
        // 콘솔 필드 이름이 isActive라서 이름을 고정한다.
        // boolean 필드는 자바 규칙상 "active"로 바뀌어 나갈 수 있어 명시한다.
        @JsonProperty("isActive") boolean isActive,
        Instant createdAt,          // ISO-8601 문자열로 나간다
        Instant deactivatedAt       // 해제한 적이 없으면 null
) {

    /** 등록자 요약. 콘솔의 AdminSummary({ adminId, username })와 같다. */
    public record AdminSummary(UUID adminId, String username) {
    }

    /** username을 모르는 곳에서 쓴다. addedBy가 있으면 username은 null로 나간다. */
    public static BlacklistItem from(BlacklistEntry entry) {
        return from(entry, null);
    }

    /**
     * 등록자의 username을 아는 곳(방금 로그인한 관리자가 등록한 응답 등)에서 쓴다.
     * addedBy가 null이면(일괄 등록 등) username을 줘도 addedBy는 null이다.
     */
    public static BlacklistItem from(BlacklistEntry entry, String addedByUsername) {
        return new BlacklistItem(
                entry.getId().getHashType().name(),
                entry.getId().getHashValue(),
                entry.getReason(),
                entry.getSeverity().name(),
                entry.getSource().name(),
                toAdminSummary(entry.getAddedBy(), addedByUsername),
                entry.isActive(),
                entry.getCreatedAt(),
                entry.getDeactivatedAt());
    }

    /** 목록 조회용. admin_users와 조인해서 읽은 행을 응답으로 바꾼다. */
    public static BlacklistItem fromRow(BlacklistListRow row) {
        return new BlacklistItem(
                row.hashType(),
                row.hashValue(),
                row.reason(),
                row.severity(),
                row.source(),
                toAdminSummary(row.addedBy(), row.addedByUsername()),
                row.active(),
                row.createdAt(),
                row.deactivatedAt());
    }

    private static AdminSummary toAdminSummary(UUID addedBy, String username) {
        return addedBy == null ? null : new AdminSummary(addedBy, username);
    }
}