package com.teecherteamc.platform.hashlist.application;

import com.teecherteamc.platform.audit.application.AuditLogRecorder;
import com.teecherteamc.platform.audit.domain.AuditAction;
import com.teecherteamc.platform.audit.domain.AuditTargetType;
import com.teecherteamc.platform.hashlist.domain.AffectedDeviceRow;
import com.teecherteamc.platform.hashlist.domain.BlacklistEntry;
import com.teecherteamc.platform.hashlist.domain.BlacklistEntryRepository;
import com.teecherteamc.platform.hashlist.domain.EntrySource;
import com.teecherteamc.platform.hashlist.domain.FindAffectedDevicesPort;
import com.teecherteamc.platform.hashlist.domain.HashKey;
import com.teecherteamc.platform.hashlist.domain.Severity;
import com.teecherteamc.platform.hashlist.domain.WhitelistEntryRepository;
import com.teecherteamc.platform.lookup.domain.BloomFilterPort;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 블랙리스트에 해시를 등록한다. (POST /api/admin/blacklist)
 *
 * 등록 즉시 차단되는 이유: 판정 사슬(CheckHash)이 블랙리스트를 가장 먼저 조회하기 때문이다.
 * 블룸 필터에도 즉시 추가한다(블룸 미스는 목록 조회를 생략시키므로 재구축을 기다리면 안 된다).
 */
@Service
public class AddBlacklistEntryUseCase {

    private final BlacklistEntryRepository blacklist;
    private final WhitelistEntryRepository whitelist;
    private final Clock clock;   // 현재 시각을 밖에서 받아야 테스트에서 고정할 수 있다
    private final BloomFilterPort bloomFilter;
    private final AuditLogRecorder auditLogRecorder;   // 관리자 쓰기 API가 공통으로 쓰는 감사 로그 기록기(#42)
    private final FindAffectedDevicesPort findAffectedDevicesPort;   // IMP-10: 악성 지정 시 영향받은 기기 조회

    public AddBlacklistEntryUseCase(BlacklistEntryRepository blacklist,
                                    WhitelistEntryRepository whitelist,
                                    Clock clock,
                                    AuditLogRecorder auditLogRecorder,
                                    BloomFilterPort bloomFilter,
                                    FindAffectedDevicesPort findAffectedDevicesPort) {
        this.bloomFilter = bloomFilter;
        this.blacklist = blacklist;
        this.whitelist = whitelist;
        this.clock = clock;
        this.auditLogRecorder = auditLogRecorder;
        this.findAffectedDevicesPort = findAffectedDevicesPort;
    }

    /**
     * 요청 값을 한 덩어리로 묶는다.
     * addedBy는 로그인한 관리자의 admin_id, ip는 감사 로그에 남길 클라이언트 IP다. 둘 다 컨트롤러가 채운다.
     * ip는 IP 리터럴이 아니거나 못 구하면 null일 수 있다.
     */
    public record Command(String sha256, String reason, UUID addedBy, String ip) {
    }

    /** affectedDevices: 이 해시를 이미 받은 적 있는 기기 목록(IMP-10). 없으면 빈 리스트. */
    public record Result(BlacklistEntry entry, List<AffectedDeviceRow> affectedDevices) {
    }

    @Transactional   // 검사와 저장과 감사 로그가 한 트랜잭션이라 중간에 예외가 나면 모두 취소된다
    public Result add(Command command) {
        // 1. 형식 검증 + 정규화. 틀리면 InvalidHashException (컨트롤러가 400으로 바꾼다)
        HashKey key = HashKey.sha256(command.sha256());
        Instant now = clock.instant();

        // 2. 화이트리스트에 있으면 거절.
        //    isEffectiveAt: 활성이면서 만료 전인 것만 충돌로 본다.
        //    스펙대로 "활성이기만 하면"으로 하려면 e.isEffectiveAt(now)를 e.isActive()로 바꾼다.
        //    (만료됐지만 활성으로 남은 행이 있으면 활성 기준에서는 그 해시를 영영 등록할 수 없다)
        whitelist.findByKey(key)
                .filter(e -> e.isEffectiveAt(now))
                .ifPresent(e -> {
                    throw new HashListConflictException("화이트리스트에 등록된 해시입니다.");
                });

        Optional<BlacklistEntry> existing = blacklist.findByKey(key);

        if (existing.isPresent()) {
            BlacklistEntry entry = existing.get();

            // 3. 이미 활성이면 거절
            if (entry.isActive()) {
                throw new HashListConflictException("이미 블랙리스트에 등록된 해시입니다.");
            }

            // 4. 해제했던 행이 있으면 새로 INSERT하지 않고 되살린다. (PK 충돌 방지)
            entry.reactivate(command.reason(), Severity.HIGH, EntrySource.MANUAL, command.addedBy());
            blacklist.save(entry);
            BloomAfterCommit.add(bloomFilter, key.getHashValue());
            recordAudit(command, key);
            return new Result(entry, findAffectedDevicesPort.findBySha256(key.getHashValue()));
        }

        // 5. 처음 등록. severity는 요청에 없어서 HIGH, source는 MANUAL
        BlacklistEntry entry = BlacklistEntry.register(
                key, command.reason(), Severity.HIGH, EntrySource.MANUAL, command.addedBy(), now);
        blacklist.save(entry);
        BloomAfterCommit.add(bloomFilter, key.getHashValue());
        recordAudit(command, key);
        // 7. 이 해시를 이미 받은 기기가 있는지 확인한다(IMP-10) — 통과시켰던 파일이 뒤늦게 악성으로 밝혀진 경우.
        return new Result(entry, findAffectedDevicesPort.findBySha256(key.getHashValue()));
    }

    /**
     * 6. 감사 로그. 등록과 재등록 둘 다 ADD_BLACKLIST로 남긴다.
     * target_id는 감사 로그 규격대로 "SHA256:해시값" 형식이다(해시 리스트 공통).
     * 기록기는 MANDATORY라서 이 메서드가 속한 add()의 트랜잭션 안에서만 동작한다.
     * 저장이 실패하면 등록도 같이 취소된다.
     */
    private void recordAudit(Command command, HashKey key) {
        String targetId = key.getHashType().name() + ":" + key.getHashValue();
        auditLogRecorder.record(
                command.addedBy(),
                AuditAction.ADD_BLACKLIST,
                AuditTargetType.BLACKLIST,
                targetId,
                null,
                command.ip());
    }
}