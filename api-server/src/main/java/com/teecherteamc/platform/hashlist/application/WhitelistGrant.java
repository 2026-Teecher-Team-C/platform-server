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
import com.teecherteamc.platform.lookup.domain.BloomFilterPort;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 다른 컨텍스트의 결정(격리 오탐 복원, 소명 승인)으로 한 해시를 통과시킨다. 두 리스너
 * (QuarantineFileRestoredListener, AppealApprovedListener)가 같은 순서·같은 후처리를 쓰도록 여기에 모았다.
 *
 * <p>순서: 해시 잠금 → 같은 해시의 활성 블랙리스트 해제(감사 REMOVE_BLACKLIST) → 화이트리스트 등록 또는
 * 재활성화 → 바뀌었으면 블룸 추가(커밋 후)·판정 무효화 이벤트·감사 ADD_WHITELIST. 호출한 쪽 트랜잭션 안에서
 * 실행되므로 그 결정과 함께 커밋되거나 함께 취소된다. 이미 유효한 화이트리스트는 건드리지 않는다(출처도 덮어쓰지 않음).
 */
@Component
class WhitelistGrant {

    /** 통과 결정의 출처. 등록·재활성화 방식과 감사 detail 문구를 정한다. */
    sealed interface Origin permits QuarantineOrigin, AppealOrigin {

        static Origin quarantine(UUID quarantineId) {
            return new QuarantineOrigin(quarantineId);
        }

        static Origin appeal(UUID appealId) {
            return new AppealOrigin(appealId);
        }

        WhitelistEntry register(HashKey key, String reason, UUID adminId, Instant now);

        void revive(WhitelistEntry entry, String reason, UUID adminId);

        Map<String, Object> blacklistReleaseDetail();

        Map<String, Object> whitelistAuditDetail(String reason);
    }

    record QuarantineOrigin(UUID quarantineId) implements Origin {

        @Override
        public WhitelistEntry register(HashKey key, String reason, UUID adminId, Instant now) {
            return WhitelistEntry.register(key, reason, adminId, quarantineId, null, now);
        }

        @Override
        public void revive(WhitelistEntry entry, String reason, UUID adminId) {
            entry.restoreFromQuarantine(reason, adminId, quarantineId);
        }

        @Override
        public Map<String, Object> blacklistReleaseDetail() {
            return Map.of("reason", "격리 파일 오탐 복원으로 자동 해제", "quarantineId", quarantineId.toString());
        }

        @Override
        public Map<String, Object> whitelistAuditDetail(String reason) {
            return Map.of("reason", reason, "source", "QUARANTINE_RESTORE", "quarantineId", quarantineId.toString());
        }
    }

    record AppealOrigin(UUID appealId) implements Origin {

        @Override
        public WhitelistEntry register(HashKey key, String reason, UUID adminId, Instant now) {
            return WhitelistEntry.registerFromAppeal(key, reason, adminId, appealId, now);
        }

        @Override
        public void revive(WhitelistEntry entry, String reason, UUID adminId) {
            entry.restoreFromAppeal(reason, adminId, appealId);
        }

        @Override
        public Map<String, Object> blacklistReleaseDetail() {
            return Map.of("reason", "소명 승인으로 자동 해제", "appealId", appealId.toString());
        }

        @Override
        public Map<String, Object> whitelistAuditDetail(String reason) {
            return Map.of("reason", reason, "source", "APPEAL_APPROVAL", "appealId", appealId.toString());
        }
    }

    private final WhitelistEntryRepository whitelist;
    private final BlacklistEntryRepository blacklist;
    private final HashListWriteLock writeLock;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditLogRecorder auditLogRecorder;
    private final BloomFilterPort bloomFilter;

    WhitelistGrant(WhitelistEntryRepository whitelist,
                   BlacklistEntryRepository blacklist,
                   HashListWriteLock writeLock,
                   ApplicationEventPublisher eventPublisher,
                   AuditLogRecorder auditLogRecorder,
                   BloomFilterPort bloomFilter) {
        this.whitelist = whitelist;
        this.blacklist = blacklist;
        this.writeLock = writeLock;
        this.eventPublisher = eventPublisher;
        this.auditLogRecorder = auditLogRecorder;
        this.bloomFilter = bloomFilter;
    }

    void grant(HashKey key, String reason, UUID adminId, String ipAddress, Origin origin, Instant now) {
        // 같은 해시를 향한 다른 등록/복원과 동시에 겹치면 PK 충돌로 500이 나므로 잠근다
        writeLock.lock(key);

        releaseConflictingBlacklist(key, adminId, ipAddress, origin, now);
        registerOrReactivateWhitelist(key, reason, adminId, ipAddress, origin, now);
    }

    // 통과 결정이 블랙리스트를 이긴다 — 막지 않고 명시적으로 해제하고 감사 로그를 남긴다(팀 결정)
    private void releaseConflictingBlacklist(HashKey key, UUID adminId, String ipAddress, Origin origin,
                                             Instant now) {
        blacklist.findByKey(key).filter(BlacklistEntry::isActive).ifPresent(entry -> {
            entry.deactivate(now);
            blacklist.save(entry);
            auditLogRecorder.record(adminId, AuditAction.REMOVE_BLACKLIST, AuditTargetType.BLACKLIST,
                    WhitelistAuditTarget.of(key), origin.blacklistReleaseDetail(), ipAddress);
        });
    }

    private void registerOrReactivateWhitelist(HashKey key, String reason, UUID adminId, String ipAddress,
                                               Origin origin, Instant now) {
        Optional<WhitelistEntry> existing = whitelist.findByKey(key);
        if (existing.isEmpty()) {
            whitelist.save(origin.register(key, reason, adminId, now));
        } else {
            WhitelistEntry entry = existing.get();
            if (entry.isEffectiveAt(now)) {
                // 이미 유효하게 등록돼 있다 — 목적은 이미 달성됐으므로 건드리지 않는다
                return;
            }
            // 해제됐거나(비활성) 만료돼서(활성이지만 유효하지 않음) 되살려야 하는 행
            if (entry.isActive()) {
                entry.deactivate();
            }
            origin.revive(entry, reason, adminId);
            whitelist.save(entry);
        }

        BloomAfterCommit.add(bloomFilter, key.getHashValue());
        // file_verdicts는 verdict 컨텍스트 소유라 직접 안 고치고 이벤트로 알린다(기존 리스너 재사용)
        eventPublisher.publishEvent(new WhitelistEntryRegistered(key.getHashValue()));
        auditLogRecorder.record(adminId, AuditAction.ADD_WHITELIST, AuditTargetType.WHITELIST,
                WhitelistAuditTarget.of(key), origin.whitelistAuditDetail(reason), ipAddress);
    }
}
