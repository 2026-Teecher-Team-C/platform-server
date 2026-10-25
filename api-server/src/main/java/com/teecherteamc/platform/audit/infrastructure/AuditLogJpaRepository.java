package com.teecherteamc.platform.audit.infrastructure;

import com.teecherteamc.platform.audit.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

interface AuditLogJpaRepository extends JpaRepository<AuditLog, Long> {}
