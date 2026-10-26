package com.teecherteamc.platform.hashlist.application;

import com.teecherteamc.platform.audit.application.AuditLogRecorder;
import com.teecherteamc.platform.audit.domain.AuditAction;
import com.teecherteamc.platform.audit.domain.AuditTargetType;
import com.teecherteamc.platform.hashlist.domain.BlacklistEntryRepository;
import com.teecherteamc.platform.hashlist.domain.BlacklistEntry;
import com.teecherteamc.platform.hashlist.domain.HashKey;
import com.teecherteamc.platform.hashlist.domain.HashListWriteLock;
import com.teecherteamc.platform.hashlist.domain.VerdictInvalidationPort;
import com.teecherteamc.platform.hashlist.domain.WhitelistEntry;
import com.teecherteamc.platform.hashlist.domain.WhitelistEntryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 화이트리스트에 해시를 직접 등록한다. (POST /api/admin/whitelist)
 * 격리와 무관한 예외용이다. 복원에 의한 등록은 복원 API가 한다.
 *
 * 등록 즉시 통과되는 이유: 판정 사슬(CheckHash)이 화이트리스트를 가장 먼저 조회하기 때문이다.
 */
@Service
public class AddWhitelistEntryUseCase {

    private final WhitelistEntryRepository whitelist;
    private final BlacklistEntryRepository blacklist;
    private final VerdictInvalidationPort verdictInvalidation;
    private final HashListWriteLock writeLock;
    private final AuditLogRecorder auditLogRecorder;
    private final Clock clock;

    public AddWhitelistEntryUseCase(WhitelistEntryRepository whitelist,
                                    BlacklistEntryRepository blacklist,
                                    VerdictInvalidationPort verdictInvalidation,
                                    HashListWriteLock writeLock,
                                    AuditLogRecorder auditLogRecorder,
                                    Clock clock) {
        this.whitelist = whitelist;
        this.blacklist = blacklist;
        this.verdictInvalidation = verdictInvalidation;
        this.writeLock = writeLock;
        this.auditLogRecorder = auditLogRecorder;
        this.clock = clock;
    }

    /** expiresAt이 null이면 무기한. addedBy는 로그인한 관리자(hash_whitelist.added_by가 NOT NULL). */
    public record Command(String sha256, String reason, Instant expiresAt, UUID addedBy, String ipAddress) {
    }

    @Transactional   // 등록, 판정 무효화, 감사 로그가 함께 커밋되거나 함께 취소된다
    public WhitelistEntry add(Command command) {
        // 1. 형식 검증 + 정규화. 틀리면 InvalidHashException (컨트롤러가 400으로 바꾼다)
        HashKey key = HashKey.sha256(command.sha256());
        Instant now = clock.instant();
        if (command.expiresAt() != null && !command.expiresAt().isAfter(now)) {
            throw new InvalidExpiryException();
        }
        // 같은 해시를 동시에 등록하면 PK 충돌로 500이 나므로 잠가서 뒤의 요청이 409를 받게 한다
        writeLock.lock(key);

        // 2. 활성 블랙리스트에 있으면 거절
        if (blacklist.findByKey(key).filter(BlacklistEntry::isActive).isPresent()) {
            throw new HashListConflictException("블랙리스트에 등록된 해시입니다.");
        }

        Optional<WhitelistEntry> existing = whitelist.findByKey(key);
        WhitelistEntry entry;
        if (existing.isPresent()) {
            entry = existing.get();
            // 3. 지금 유효한 항목이 있으면 거절
            if (entry.isEffectiveAt(now)) {
                throw new HashListConflictException("이미 화이트리스트에 등록된 해시입니다.");
            }
            // 4. 해제했거나 만료된 행은 새로 INSERT하지 않고 되살린다. (PK 충돌 방지)
            //    만료됐지만 활성으로 남은 행은 목록 기본 조회에도 안 보이고 통과도 안 되므로 재등록을 막지 않는다.
            if (entry.isActive()) {
                entry.deactivate();
            }
            entry.reactivate(command.reason(), command.addedBy(), command.expiresAt());
        } else {
            // 5. 처음 등록
            entry = WhitelistEntry.register(key, command.reason(), command.addedBy(), null,
                    command.expiresAt(), now);
        }
        whitelist.save(entry);

        // 6. 이전 차단 판정을 쓰지 않게 한다
        verdictInvalidation.markStale(key.getHashValue());

        // 7. 감사 로그
        Map<String, Object> detail = new HashMap<>();
        detail.put("reason", command.reason());
        detail.put("expiresAt", command.expiresAt() == null ? null : command.expiresAt().toString());
        auditLogRecorder.record(command.addedBy(), AuditAction.ADD_WHITELIST, AuditTargetType.WHITELIST,
                WhitelistAuditTarget.of(key), detail, command.ipAddress());
        return entry;
    }
}
