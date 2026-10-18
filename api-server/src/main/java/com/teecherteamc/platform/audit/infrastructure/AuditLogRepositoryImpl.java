package com.teecherteamc.platform.audit.infrastructure;

import com.teecherteamc.platform.audit.domain.AuditLog;
import com.teecherteamc.platform.audit.domain.AuditLogRepository;
import org.springframework.stereotype.Repository;

@Repository
class AuditLogRepositoryImpl implements AuditLogRepository {

    private final AuditLogJpaRepository jpaRepository;

    AuditLogRepositoryImpl(AuditLogJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public AuditLog save(AuditLog auditLog) {
        return jpaRepository.save(auditLog);
    }
}
