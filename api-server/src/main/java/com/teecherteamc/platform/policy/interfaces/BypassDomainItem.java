package com.teecherteamc.platform.policy.interfaces;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.teecherteamc.platform.policy.domain.BypassDomainRow;
import java.time.Instant;
import java.util.UUID;

/**
 * 바이패스 도메인 한 건의 응답. 콘솔 console/src/lib/api/policies.ts의 항목 타입과 필드 이름이 같다.
 *
 * updatedAt은 콘솔 타입에서 선택(?) 필드인데 bypass_domains에 그 컬럼이 없어서 내리지 않는다.
 */
public record BypassDomainItem(
        UUID bypassId,
        String host,
        String category,            // PINNED | SECURITY_UPDATE
        String reason,
        // 콘솔 필드 이름이 isActive라서 이름을 고정한다. (BlacklistItem과 같은 이유)
        @JsonProperty("isActive") boolean isActive,
        Instant createdAt) {

    static BypassDomainItem from(BypassDomainRow row) {
        return new BypassDomainItem(
                row.bypassId(), row.host(), row.category(), row.reason(), row.active(), row.createdAt());
    }
}
