package com.teecherteamc.platform.hashlist.application;

import com.teecherteamc.platform.audit.application.AuditLogRecorder;
import com.teecherteamc.platform.audit.domain.AuditAction;
import com.teecherteamc.platform.audit.domain.AuditTargetType;
import com.teecherteamc.platform.hashlist.domain.BlacklistEntry;
import com.teecherteamc.platform.hashlist.domain.BlacklistEntryRepository;
import com.teecherteamc.platform.hashlist.domain.HashKey;
import com.teecherteamc.platform.hashlist.domain.HashListWriteLock;
import com.teecherteamc.platform.hashlist.domain.WhitelistEntry;
import com.teecherteamc.platform.hashlist.domain.WhitelistEntryRegistered;
import com.teecherteamc.platform.hashlist.domain.WhitelistEntryRepository;
import com.teecherteamc.platform.quarantine.domain.QuarantineFileRestored;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 격리 파일 복원을 받아 화이트리스트를 등록(또는 재활성화)하고, 같은 해시에 활성 블랙리스트가
 * 있으면 해제한다. quarantine이 hashlist 애그리거트를 직접 건드리지 않도록 여기서 대신 처리한다.
 *
 * <p>커밋 후가 아니라 발행한 쪽(복원) 트랜잭션 안에서 바로 처리한다(@EventListener) — 격리 상태
 * 변경, 화이트리스트 등록, 블랙리스트 해제가 함께 커밋되거나 함께 취소된다.
 */
@Component
class QuarantineFileRestoredListener {

    private final WhitelistEntryRepository whitelist;
    private final BlacklistEntryRepository blacklist;
    private final HashListWriteLock writeLock;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditLogRecorder auditLogRecorder;
    private final Clock clock;

    QuarantineFileRestoredListener(WhitelistEntryRepository whitelist,
                                   BlacklistEntryRepository blacklist,
                                   HashListWriteLock writeLock,
                                   ApplicationEventPublisher eventPublisher,
                                   AuditLogRecorder auditLogRecorder,
                                   Clock clock) {
        this.whitelist = whitelist;
        this.blacklist = blacklist;
        this.writeLock = writeLock;
        this.eventPublisher = eventPublisher;
        this.auditLogRecorder = auditLogRecorder;
        this.clock = clock;
    }

    @EventListener
    void on(QuarantineFileRestored event) {
        HashKey key = HashKey.sha256(event.sha256());
        Instant now = clock.instant();
        // 같은 해시를 향한 다른 등록/복원과 동시에 겹치면 PK 충돌로 500이 나므로 잠근다
        writeLock.lock(key);

        deactivateConflictingBlacklist(key, event, now);
        registerOrReactivateWhitelist(key, event, now);
    }

    // 복원이 블랙리스트를 이긴다 — 막지 않고 명시적으로 해제하고 감사 로그를 남긴다(팀 결정)
    private void deactivateConflictingBlacklist(HashKey key, QuarantineFileRestored event, Instant now) {
        blacklist.findByKey(key).filter(BlacklistEntry::isActive).ifPresent(entry -> {
            entry.deactivate(now);
            blacklist.save(entry);
            auditLogRecorder.record(event.adminId(), AuditAction.REMOVE_BLACKLIST, AuditTargetType.BLACKLIST,
                    WhitelistAuditTarget.of(key),
                    Map.of("reason", "격리 파일 오탐 복원으로 자동 해제", "quarantineId", event.quarantineId().toString()),
                    event.ipAddress());
        });
    }

    private void registerOrReactivateWhitelist(HashKey key, QuarantineFileRestored event, Instant now) {
        Optional<WhitelistEntry> existing = whitelist.findByKey(key);
        boolean whitelistChanged;
        if (existing.isEmpty()) {
            WhitelistEntry entry = WhitelistEntry.register(
                    key, event.reason(), event.adminId(), event.quarantineId(), null, now);
            whitelist.save(entry);
            whitelistChanged = true;
        } else {
            WhitelistEntry entry = existing.get();
            if (entry.isEffectiveAt(now)) {
                // 이미 유효하게 등록돼 있다 — 복원의 목적은 이미 달성됐으므로 건드리지 않는다
                whitelistChanged = false;
            } else {
                // 해제됐거나(비활성) 만료돼서(활성이지만 유효하지 않음) 되살려야 하는 행
                if (entry.isActive()) {
                    entry.deactivate();
                }
                entry.restoreFromQuarantine(event.reason(), event.adminId(), event.quarantineId());
                whitelist.save(entry);
                whitelistChanged = true;
            }
        }

        if (whitelistChanged) {
            // file_verdicts는 verdict 컨텍스트 소유라 직접 안 고치고 이벤트로 알린다(기존 리스너 재사용)
            eventPublisher.publishEvent(new WhitelistEntryRegistered(key.getHashValue()));
            auditLogRecorder.record(event.adminId(), AuditAction.ADD_WHITELIST, AuditTargetType.WHITELIST,
                    WhitelistAuditTarget.of(key),
                    Map.of("reason", event.reason(), "source", "QUARANTINE_RESTORE",
                            "quarantineId", event.quarantineId().toString()),
                    event.ipAddress());
        }
    }
}
