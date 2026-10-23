package com.teecherteamc.platform.audit.application;

import com.teecherteamc.platform.audit.domain.AuditLog;
import com.teecherteamc.platform.audit.domain.AuditLogRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 관리자 행위를 감사 로그(audit_logs)에 한 줄 남긴다.
 *
 * 호출하는 쪽의 트랜잭션에 같이 묶인다. 그래서 로그 저장이 실패하면 본 작업도 취소된다.
 * action / target_type 값은 설계 레포의 감사 로그 포맷을 따른다.
 */
@Service
public class RecordAuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final Clock clock;

    public RecordAuditLogService(AuditLogRepository auditLogRepository, Clock clock) {
        this.auditLogRepository = auditLogRepository;
        this.clock = clock;
    }

    /**
     * @param actorId    행위한 관리자 id. 로그인 연동 전에는 null
     * @param action     행위 종류 (예: ADD_BLACKLIST)
     * @param targetType 대상 종류
     * @param targetId   대상 식별자 (예: 해시 값). 없으면 null
     * @param detail     부가 정보 JSON 문자열. 없으면 null
     */
    public void record(UUID actorId, String action, String targetType,
                       String targetId, String detail) {
        Instant now = Instant.now(clock);
        auditLogRepository.save(AuditLog.record(actorId, action, targetType, targetId, detail, now));
    }
}