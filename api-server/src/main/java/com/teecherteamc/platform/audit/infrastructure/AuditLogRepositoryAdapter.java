package com.teecherteamc.platform.audit.infrastructure;

import com.teecherteamc.platform.audit.domain.AuditLog;
import com.teecherteamc.platform.audit.domain.AuditLogRepository;
import org.springframework.stereotype.Repository;

/**
 * AuditLogRepository의 실제 구현. 저장은 Spring Data JPA에 맡긴다.
 */
@Repository
public class AuditLogRepositoryAdapter implements AuditLogRepository {

    private final AuditLogJpaRepository jpaRepository;

    public AuditLogRepositoryAdapter(AuditLogJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public AuditLog save(AuditLog auditLog) {
        return jpaRepository.save(auditLog);
    }
}