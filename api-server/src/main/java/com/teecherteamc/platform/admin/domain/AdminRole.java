package com.teecherteamc.platform.admin.domain;

/**
 * admin_users.role 값. 권한은 역할별로 나뉜다(docs/auth-policy.md 9장).
 *
 * <p>USER는 콘솔 계정이 없는 일반 직원(소명 토큰 사용자)이라 내 정보 보기·로그아웃 외의 콘솔 API 권한이 없다.
 */
public enum AdminRole {
    ADMIN,
    ANALYST,
    USER
}
