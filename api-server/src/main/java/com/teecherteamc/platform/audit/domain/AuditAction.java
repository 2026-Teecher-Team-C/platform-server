package com.teecherteamc.platform.audit.domain;

import java.util.Set;

/**
 * audit_logs.action 값. 설계 레포 감사 로그 규격(2026-09-18-audit-log-spec.md) 2장의 목록에, 기능 API 명세
 * "감사 로그 조회"가 이번 범위로 더한 ISSUE_ENROLLMENT_TOKEN·UPDATE_WHITELIST·UPDATE_BLACKLIST를 합쳤다.
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
    UPDATE_WHITELIST,
    REMOVE_WHITELIST,
    ADD_BLACKLIST,
    UPDATE_BLACKLIST,
    REMOVE_BLACKLIST,
    // 격리
    RESTORE_QUARANTINE,
    DELETE_QUARANTINE,
    // 룰셋 / 룰
    UPLOAD_RULESET,
    ACTIVATE_RULESET,
    ENABLE_YARA_RULE,
    DISABLE_YARA_RULE,
    // 정책
    ADD_BYPASS_DOMAIN,
    REMOVE_BYPASS_DOMAIN,
    UPDATE_FILE_TYPE_POLICY,
    // 판정 / 에이전트 / 캐시
    SET_MANUAL_VERDICT,
    ISSUE_ENROLLMENT_TOKEN,
    REVOKE_AGENT,
    INVALIDATE_CACHE;

    // 검사를 약하게 만드는 조작. 기능 API 명세 "감사 로그 조회" 조회 로직 3번의 4종이다.
    // (감사 로그 규격 2장의 ⚠️ 7종 중 이번 범위에서 실제로 기록하는 것만 남겼다)
    private static final Set<String> DANGEROUS_NAMES = Set.of(
            ADD_WHITELIST.name(), REMOVE_BLACKLIST.name(), RESTORE_QUARANTINE.name(), ADD_BYPASS_DOMAIN.name());

    /** audit_logs.action 문자열이 위험 조작인지. 이 enum에 없는 값이면 false다. */
    public static boolean isDangerous(String action) {
        return action != null && DANGEROUS_NAMES.contains(action);
    }

    /** dangerousOnly 조회 조건에 쓰는 위험 조작 이름들. */
    public static Set<String> dangerousNames() {
        return DANGEROUS_NAMES;
    }
}
