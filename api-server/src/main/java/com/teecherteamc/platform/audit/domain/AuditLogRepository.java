package com.teecherteamc.platform.audit.domain;

public interface AuditLogRepository {

    AuditLog save(AuditLog auditLog);
}
