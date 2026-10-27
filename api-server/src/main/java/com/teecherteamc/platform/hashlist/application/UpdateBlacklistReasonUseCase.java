package com.teecherteamc.platform.hashlist.application;

import com.teecherteamc.platform.audit.application.AuditLogRecorder;
import com.teecherteamc.platform.audit.domain.AuditAction;
import com.teecherteamc.platform.audit.domain.AuditTargetType;
import com.teecherteamc.platform.hashlist.domain.BlacklistEntry;
import com.teecherteamc.platform.hashlist.domain.BlacklistEntryRepository;
import com.teecherteamc.platform.hashlist.domain.HashKey;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 블랙리스트 항목의 등록 사유를 고친다. (PATCH /api/admin/blacklist/{sha256})
 *
 * 사유(reason)만 바꾼다. 심각도·출처·등록자·등록 시각·활성 여부는 그대로다.
 * 활성 항목만 고칠 수 있다. 해제된 항목이나 없는 해시는 BlacklistEntryNotFoundException(404)이다.
 */
@Service
public class UpdateBlacklistReasonUseCase {

    private final BlacklistEntryRepository blacklist;
    private final AuditLogRecorder auditLogRecorder;   // 관리자 쓰기 API가 공통으로 쓰는 감사 로그 기록기

    public UpdateBlacklistReasonUseCase(BlacklistEntryRepository blacklist,
                                        AuditLogRecorder auditLogRecorder) {
        this.blacklist = blacklist;
        this.auditLogRecorder = auditLogRecorder;
    }

    /**
     * 요청 값을 한 덩어리로 묶는다.
     * actorId는 로그인한 관리자의 admin_id(수정한 사람), ip는 감사 로그에 남길 클라이언트 IP다. 둘 다 컨트롤러가 채운다.
     * ip는 못 구하면 null일 수 있다.
     */
    public record Command(String sha256, String reason, UUID actorId, String ip) {
    }

    @Transactional   // 조회·수정·감사 로그가 한 트랜잭션이라, 감사 로그 저장이 실패하면 수정도 같이 취소된다
    public BlacklistEntry update(Command command) {
        // 1. 형식 검증 + 정규화(소문자). 틀리면 InvalidHashException (컨트롤러가 400으로 바꾼다)
        HashKey key = HashKey.sha256(command.sha256());

        // 2. 활성 항목만 찾는다. 해제된 항목(is_active = false)도 "없음"으로 보고 404로 보낸다.
        BlacklistEntry entry = blacklist.findByKey(key)
                .filter(BlacklistEntry::isActive)
                .orElseThrow(() -> new BlacklistEntryNotFoundException("활성 상태의 블랙리스트 항목이 없습니다."));

        // 3. 바뀌기 전 사유를 먼저 꺼내 둔다. (감사 로그에 이전·이후를 남기려고)
        String previousReason = entry.getReason();
        entry.changeReason(command.reason());
        blacklist.save(entry);

        // 4. 감사 로그. 기록기는 MANDATORY라서 이 메서드의 트랜잭션 안에서만 동작한다.
        recordAudit(command, key, previousReason, entry.getReason());
        return entry;
    }

    /** target_id는 감사 로그 규격대로 "SHA256:해시값"이다(해시 리스트 공통). */
    private void recordAudit(Command command, HashKey key, String previousReason, String newReason) {
        String targetId = key.getHashType().name() + ":" + key.getHashValue();
        auditLogRecorder.record(
                command.actorId(),
                AuditAction.UPDATE_BLACKLIST,
                AuditTargetType.BLACKLIST,
                targetId,
                Map.of("previousReason", previousReason, "newReason", newReason),
                command.ip());
    }
}