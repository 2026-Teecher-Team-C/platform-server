package com.teecherteamc.platform.quarantine.application;

import com.teecherteamc.platform.audit.application.AuditLogRecorder;
import com.teecherteamc.platform.audit.domain.AuditAction;
import com.teecherteamc.platform.audit.domain.AuditTargetType;
import com.teecherteamc.platform.hashlist.domain.HashKey;
import com.teecherteamc.platform.hashlist.domain.HashListWriteLock;
import com.teecherteamc.platform.hashlist.domain.WhitelistEntry;
import com.teecherteamc.platform.hashlist.domain.WhitelistEntryRegistered;
import com.teecherteamc.platform.hashlist.domain.WhitelistEntryRepository;
import com.teecherteamc.platform.quarantine.domain.QuarantineFile;
import com.teecherteamc.platform.quarantine.domain.QuarantineFileRepository;
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
 * <p>이미 활성 화이트리스트인 경우 409를 주지 않고 조용히 통과시킨다 — 복원의 목적("최종적으로
 * 화이트리스트에 있게 만드는 것")이 이미 달성된 상태라 막을 이유가 없다(PR#51 리뷰 메모).
 */
@Service
public class RestoreQuarantineFileUseCase {

    private final QuarantineFileRepository quarantineFileRepository;
    private final WhitelistEntryRepository whitelistEntryRepository;
    private final HashListWriteLock writeLock;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final AuditLogRecorder auditLogRecorder;

    public RestoreQuarantineFileUseCase(QuarantineFileRepository quarantineFileRepository,
                                        WhitelistEntryRepository whitelistEntryRepository,
                                        HashListWriteLock writeLock,
                                        ApplicationEventPublisher eventPublisher,
                                        Clock clock,
                                        AuditLogRecorder auditLogRecorder) {
        this.quarantineFileRepository = quarantineFileRepository;
        this.whitelistEntryRepository = whitelistEntryRepository;
        this.writeLock = writeLock;
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

        HashKey key = HashKey.sha256(file.getSha256());
        // 같은 해시를 향한 다른 등록/복원과 동시에 겹치면 PK 충돌로 500이 나므로 잠근다
        writeLock.lock(key);

        Optional<WhitelistEntry> existing = whitelistEntryRepository.findByKey(key);
        boolean whitelistChanged;
        if (existing.isEmpty()) {
            WhitelistEntry entry = WhitelistEntry.register(key, reason, adminId, quarantineId, null, now);
            whitelistEntryRepository.save(entry);
            whitelistChanged = true;
        } else {
            WhitelistEntry entry = existing.get();
            if (entry.isActive()) {
                // 이미 활성 상태 — 복원의 목적은 이미 달성됐으므로 건드리지 않는다(409 없음)
                whitelistChanged = false;
            } else {
                entry.restoreFromQuarantine(reason, adminId, quarantineId);
                whitelistEntryRepository.save(entry);
                whitelistChanged = true;
            }
        }

        if (whitelistChanged) {
            // file_verdicts는 verdict 컨텍스트 소유라 직접 안 고치고 이벤트로 알린다(기존 리스너 재사용)
            eventPublisher.publishEvent(new WhitelistEntryRegistered(key.getHashValue()));
        }

        quarantineFileRepository.save(file);

        auditLogRecorder.record(adminId, AuditAction.RESTORE_QUARANTINE, AuditTargetType.QUARANTINE,
                quarantineId.toString(), Map.of("sha256", file.getSha256()), ipAddress);

        return Optional.of(new Result(quarantineId, file.getSha256(), file.getStatus(), true, file.getRestoredAt()));
    }
}
