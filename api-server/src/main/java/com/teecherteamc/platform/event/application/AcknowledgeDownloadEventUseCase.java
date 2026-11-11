package com.teecherteamc.platform.event.application;

import com.teecherteamc.platform.audit.application.AuditLogRecorder;
import com.teecherteamc.platform.audit.domain.AuditAction;
import com.teecherteamc.platform.audit.domain.AuditTargetType;
import com.teecherteamc.platform.event.domain.AcknowledgeDownloadEventPort;
import com.teecherteamc.platform.event.domain.DownloadEventAcknowledgement;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 차단 이벤트를 확인 처리한다. (POST /api/admin/download-events/{eventId}/acknowledge)
 *
 * <p>멱등이다. 이미 확인된 이벤트면 처음 확인한 사람·시각을 그대로 돌려주고 감사 로그는 남기지 않는다.
 * 동시에 여러 요청이 와도 INSERT … ON CONFLICT DO NOTHING이 행을 하나만 남기고, 실제로 넣은 요청 하나만
 * 감사 로그를 쓴다. 나중 요청은 먼저 요청의 커밋을 기다렸다가(같은 PK) 아무것도 넣지 않고, 이어지는 조회는
 * READ COMMITTED라 커밋된 행을 읽는다.
 */
@Service
public class AcknowledgeDownloadEventUseCase {

    private final AcknowledgeDownloadEventPort port;
    private final AuditLogRecorder auditLogRecorder;
    private final Clock clock;

    public AcknowledgeDownloadEventUseCase(AcknowledgeDownloadEventPort port, AuditLogRecorder auditLogRecorder,
                                           Clock clock) {
        this.port = port;
        this.auditLogRecorder = auditLogRecorder;
        this.clock = clock;
    }

    @Transactional
    public DownloadEventAcknowledgement acknowledge(UUID eventId, UUID adminId, String ipAddress) {
        boolean blocked = port.isBlocked(eventId).orElseThrow(() -> new DownloadEventNotFoundException(eventId));
        if (!blocked) {
            throw new NotBlockedEventException(eventId);
        }
        if (port.insertIfAbsent(eventId, adminId, clock.instant())) {
            auditLogRecorder.record(adminId, AuditAction.DOWNLOAD_EVENT_ACKNOWLEDGE, AuditTargetType.DOWNLOAD_EVENT,
                    eventId.toString(), null, ipAddress);
        }
        return port.find(eventId);
    }
}
