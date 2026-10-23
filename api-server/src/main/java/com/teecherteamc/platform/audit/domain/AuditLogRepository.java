package com.teecherteamc.platform.audit.domain;

/**
 * 감사 로그 저장소가 제공해야 하는 기능.
 * 실제 DB 접근은 infrastructure 폴더의 AuditLogRepositoryAdapter가 한다.
 */
public interface AuditLogRepository {

    /** 감사 로그 1건을 저장한다. 한번 저장한 로그는 수정하지 않는다. */
    AuditLog save(AuditLog auditLog);
}