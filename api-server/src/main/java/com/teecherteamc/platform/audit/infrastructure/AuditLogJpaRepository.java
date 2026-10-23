package com.teecherteamc.platform.audit.infrastructure;

import com.teecherteamc.platform.audit.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * audit_logs 테이블용 Spring Data JPA 저장소.
 * 구현은 Spring이 만든다. 도메인에서는 직접 쓰지 않고 AuditLogRepositoryAdapter를 통해서만 쓴다.
 */
interface AuditLogJpaRepository extends JpaRepository<AuditLog, Long> {
}