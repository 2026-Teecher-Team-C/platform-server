package com.teecherteamc.platform.policy.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * bypass_domains 한 행을 목록용으로 읽은 값. 읽기 전용이라 BypassDomain 애그리거트를 안 거친다.
 * domain_pattern 컬럼은 와일드카드가 금지돼 있어(ck_bypass_domains_exact_host) 정확한 호스트이므로 host로 부른다.
 */
public record BypassDomainRow(
        UUID bypassId,
        String host,
        String category,
        String reason,
        boolean active,
        Instant createdAt) {
}
