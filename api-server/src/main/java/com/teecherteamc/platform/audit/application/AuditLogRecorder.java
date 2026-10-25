package com.teecherteamc.platform.audit.application;

import com.teecherteamc.platform.audit.domain.AuditAction;
import com.teecherteamc.platform.audit.domain.AuditLog;
import com.teecherteamc.platform.audit.domain.AuditLogRepository;
import com.teecherteamc.platform.audit.domain.AuditTargetType;
import com.teecherteamc.platform.common.ClientIp;
import java.net.InetAddress;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 관리자 쓰기 API가 공통으로 쓰는 감사 로그 기록기.
 *
 * <p>MANDATORY: 반드시 호출한 쪽의 트랜잭션 안에서 기록한다. 본 작업이 롤백되면 감사 로그도 같이 사라지고,
 * 감사 로그 INSERT가 실패하면 본 작업도 커밋되지 않는다. 트랜잭션 없이 부르면 예외가 난다 — 기록이
 * 조용히 따로 커밋되는 실수를 막기 위해서다.
 *
 * <p>detail에는 비밀번호·토큰을 넣지 않는다(감사 로그 규격 4장). 기록기는 내용을 걸러 주지 않는다.
 */
@Service
public class AuditLogRecorder {

    private final AuditLogRepository auditLogRepository;
    private final JsonMapper jsonMapper;

    public AuditLogRecorder(AuditLogRepository auditLogRepository, JsonMapper jsonMapper) {
        this.auditLogRepository = auditLogRepository;
        this.jsonMapper = jsonMapper;
    }

    /**
     * @param actorId    행위자 admin_id. 주체가 확정되지 않았으면(LOGIN_FAILED 등) null
     * @param targetId   target_type이 정한 형식의 대상 식별자. 없으면 null
     * @param detail     JSON으로 저장할 변경 전/후 등. 없으면 null
     * @param ipAddress  클라이언트 IP 문자열(ClientIp.resolve). IP 리터럴이 아니면 IP 없이 기록한다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID actorId, AuditAction action, AuditTargetType targetType, String targetId,
                       Map<String, ?> detail, String ipAddress) {
        String detailJson = detail == null ? null : jsonMapper.writeValueAsString(detail);
        InetAddress ip = ClientIp.parseLiteral(ipAddress).orElse(null);
        auditLogRepository.save(AuditLog.of(actorId, action, targetType, targetId, detailJson, ip, Instant.now()));
    }
}
