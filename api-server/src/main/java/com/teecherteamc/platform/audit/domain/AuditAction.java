package com.teecherteamc.platform.audit.domain;

/**
 * audit_logs.action 값. 설계 레포 감사 로그 규격(2026-09-18-audit-log-spec.md) 2장의 목록이다.
 * 같은 행위를 구현자마다 다른 문자열로 남기면 집계가 안 되므로 문자열을 직접 넘기지 않고 이 값을 쓴다.
 */
public enum AuditAction {
    // 인증
    LOGIN,
    LOGIN_FAILED,
    LOGOUT,
    // 계정 관리
    CREATE_ADMIN,
    UPDATE_ADMIN_ROLE,
    DEACTIVATE_ADMIN,
    // 해시 리스트
    ADD_WHITELIST,
    REMOVE_WHITELIST,
    ADD_BLACKLIST,
    REMOVE_BLACKLIST,
    // 격리
    RESTORE_QUARANTINE,
    DELETE_QUARANTINE,
    // 룰셋 / 룰
    ACTIVATE_RULESET,
    ENABLE_YARA_RULE,
    DISABLE_YARA_RULE,
    // 정책
    ADD_BYPASS_DOMAIN,
    REMOVE_BYPASS_DOMAIN,
    UPDATE_FILE_TYPE_POLICY,
    // 판정 / 에이전트 / 캐시
    SET_MANUAL_VERDICT,
    REVOKE_AGENT,
    INVALIDATE_CACHE
}
