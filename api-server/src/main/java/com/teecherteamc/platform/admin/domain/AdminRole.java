package com.teecherteamc.platform.admin.domain;

/** admin_users.role 값. 지금은 모든 관리자가 같은 권한이라 역할로 막지 않는다(docs/auth-policy.md 1장). */
public enum AdminRole {
    ADMIN,
    ANALYST,
    VIEWER
}
