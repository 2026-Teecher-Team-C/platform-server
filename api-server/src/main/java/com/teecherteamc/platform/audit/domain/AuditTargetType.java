package com.teecherteamc.platform.audit.domain;

/**
 * audit_logs.target_type 값. 감사 로그 규격 1장의 11종에 ENROLLMENT_TOKEN(ISSUE_ENROLLMENT_TOKEN의 대상,
 * target_id는 enrollment_token_id)을 더했다. target_id 형식은 이 값이 정한다
 * (예: WHITELIST는 "SHA256:해시값", LOGIN_FAILED의 ADMIN_USER는 시도한 계정명).
 */
public enum AuditTargetType {
    ADMIN_USER,
    AGENT,
    ENROLLMENT_TOKEN,
    WHITELIST,
    BLACKLIST,
    QUARANTINE,
    RULESET,
    YARA_RULE,
    BYPASS_DOMAIN,
    FILE_TYPE_POLICY,
    FILE_VERDICT,
    CACHE
}
