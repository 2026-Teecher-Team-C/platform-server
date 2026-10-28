package com.teecherteamc.platform.quarantine.application;

import com.teecherteamc.platform.audit.application.AuditLogRecorder;
import com.teecherteamc.platform.audit.domain.AuditAction;
import com.teecherteamc.platform.audit.domain.AuditTargetType;
import com.teecherteamc.platform.quarantine.domain.QuarantineFile;
import com.teecherteamc.platform.quarantine.domain.QuarantineFileRepository;
import com.teecherteamc.platform.quarantine.domain.QuarantineFileRestored;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 격리 파일을 오탐으로 판단해 복원한다. (POST /api/admin/quarantine/{quarantineId}/restore)
 *
 * <p>화이트리스트 등록/블랙리스트 해제는 hashlist 컨텍스트 소유라 여기서 직접 하지 않고
 * {@link QuarantineFileRestored} 이벤트로 알린다 — 동기 리스너라 같은 트랜잭션 안에서 처리된다
 * (hashlist.application.QuarantineFileRestoredListener). 그래서 응답의 whitelisted는 항상 true다:
 * 리스너가 예외 없이 끝나면 복원 목적(그 해시가 화이트리스트에 있는 상태)은 항상 달성된다.
 */
@Service
public class RestoreQuarantineFileUseCase {

    private final QuarantineFileRepository quarantineFileRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final AuditLogRecorder auditLogRecorder;

    public RestoreQuarantineFileUseCase(QuarantineFileRepository quarantineFileRepository,
                                        ApplicationEventPublisher eventPublisher,
                                        Clock clock,
                                        AuditLogRecorder auditLogRecorder) {
        this.quarantineFileRepository = quarantineFileRepository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.auditLogRecorder = auditLogRecorder;
    }

    public record Result(UUID quarantineId, String sha256, String status, boolean whitelisted, Instant restoredAt) {
    }

    @Transactional
    public Optional<Result> restore(UUID quarantineId, String reason, UUID adminId, String ipAddress) {
        Optional<QuarantineFile> found = quarantineFileRepository.findById(quarantineId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        QuarantineFile file = found.get();
        Instant now = clock.instant();
        file.restore(adminId, reason, now);   // QUARANTINED가 아니면 QuarantineNotRestorableException(컨트롤러가 409로)

        // hashlist의 화이트리스트 등록/블랙리스트 해제는 리스너가 같은 트랜잭션 안에서 동기로 처리한다
        eventPublisher.publishEvent(new QuarantineFileRestored(quarantineId, file.getSha256(), reason, adminId, ipAddress));

        quarantineFileRepository.save(file);

        auditLogRecorder.record(adminId, AuditAction.RESTORE_QUARANTINE, AuditTargetType.QUARANTINE,
                quarantineId.toString(), Map.of("sha256", file.getSha256()), ipAddress);

        return Optional.of(new Result(quarantineId, file.getSha256(), file.getStatus(), true, file.getRestoredAt()));
    }
}
