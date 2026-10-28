package com.teecherteamc.platform.hashlist.application;

import com.teecherteamc.platform.audit.application.AuditLogRecorder;
import com.teecherteamc.platform.audit.domain.AuditAction;
import com.teecherteamc.platform.audit.domain.AuditTargetType;
import com.teecherteamc.platform.hashlist.domain.BlacklistEntry;
import com.teecherteamc.platform.hashlist.domain.BlacklistEntryRepository;
import com.teecherteamc.platform.hashlist.domain.HashKey;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 블랙리스트 항목을 해제한다. (DELETE /api/admin/blacklist/{sha256})
 *
 * 행을 지우지 않고 is_active=false, deactivated_at=now로 바꾼다(soft delete).
 * 누가 언제 등록·해제했는지 이력이 남고, 같은 해시를 다시 등록하면 그 행을 되살린다(reactivate).
 * 활성 항목만 해제할 수 있다. 없는 해시나 이미 해제된 항목은 BlacklistEntryNotFoundException(404)이다.
 */
@Service
public class RemoveBlacklistEntryUseCase {

    private final BlacklistEntryRepository blacklist;
    private final Clock clock;   // 현재 시각을 밖에서 받아야 테스트에서 고정할 수 있다
    private final AuditLogRecorder auditLogRecorder;   // 관리자 쓰기 API가 공통으로 쓰는 감사 로그 기록기

    public RemoveBlacklistEntryUseCase(BlacklistEntryRepository blacklist,
                                       Clock clock,
                                       AuditLogRecorder auditLogRecorder) {
        this.blacklist = blacklist;
        this.clock = clock;
        this.auditLogRecorder = auditLogRecorder;
    }

    /**
     * 요청 값을 한 덩어리로 묶는다.
     * actorId는 로그인한 관리자의 admin_id(해제한 사람), ip는 감사 로그에 남길 클라이언트 IP다. 둘 다 컨트롤러가 채운다.
     * ip는 못 구하면 null일 수 있다.
     */
    public record Command(String sha256, UUID actorId, String ip) {
    }

    @Transactional   // 해제와 감사 로그가 한 트랜잭션이라, 감사 로그 저장이 실패하면 해제도 같이 취소된다
    public void remove(Command command) {
        // 1. 형식 검증 + 정규화(소문자). 틀리면 InvalidHashException (컨트롤러가 400으로 바꾼다)
        HashKey key = HashKey.sha256(command.sha256());

        // 2. 활성 항목만 찾는다. 이미 해제된 항목도 "없음"으로 보고 404로 보낸다.
        BlacklistEntry entry = blacklist.findByKey(key)
                .filter(BlacklistEntry::isActive)
                .orElseThrow(() -> new BlacklistEntryNotFoundException("활성 상태의 블랙리스트 항목이 없습니다."));

        // 3. 해제. is_active=false, deactivated_at=지금. 행은 남는다.
        entry.deactivate(clock.instant());
        blacklist.save(entry);

        // 4. 감사 로그
        recordAudit(command, key);
    }

    /**
     * target_id는 감사 로그 규격대로 "SHA256:해시값"이다(해시 리스트 공통).
     * 카드에 detail이 없어서 null로 둔다. (등록 ADD_BLACKLIST와 같다)
     * 기록기는 MANDATORY라서 remove()의 트랜잭션 안에서만 동작한다.
     */
    private void recordAudit(Command command, HashKey key) {
        String targetId = key.getHashType().name() + ":" + key.getHashValue();
        auditLogRecorder.record(
                command.actorId(),
                AuditAction.REMOVE_BLACKLIST,
                AuditTargetType.BLACKLIST,
                targetId,
                null,
                command.ip());
    }
}