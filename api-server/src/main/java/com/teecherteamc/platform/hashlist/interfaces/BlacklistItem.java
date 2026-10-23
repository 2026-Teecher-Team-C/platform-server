package com.teecherteamc.platform.hashlist.interfaces;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.teecherteamc.platform.hashlist.domain.BlacklistEntry;
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

    public static BlacklistItem from(BlacklistEntry entry) {
        return new BlacklistItem(
                entry.getId().getHashType().name(),
                entry.getId().getHashValue(),
                entry.getReason(),
                entry.getSeverity().name(),
                entry.getSource().name(),
                toAdminSummary(entry.getAddedBy()),
                entry.isActive(),
                entry.getCreatedAt(),
                entry.getDeactivatedAt());
    }

    /**
     * 지금은 로그인이 없어서 addedBy가 항상 null이다. (hash_blacklist.added_by가 nullable)
     * 인증을 붙인 뒤에는 admin_users에서 username을 조회해 채워야 한다.
     * 그 전까지 값이 있는 행이 생기면 username은 null로 나간다. (콘솔 타입은 문자열을 기대한다)
     */
    private static AdminSummary toAdminSummary(UUID addedBy) {
        return addedBy == null ? null : new AdminSummary(addedBy, null);
    }
}