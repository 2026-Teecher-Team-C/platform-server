package com.teecherteamc.platform.hashlist.application;

import com.teecherteamc.platform.audit.application.AuditLogRecorder;
import com.teecherteamc.platform.audit.domain.AuditAction;
import com.teecherteamc.platform.audit.domain.AuditTargetType;
import com.teecherteamc.platform.hashlist.domain.HashKey;
import com.teecherteamc.platform.hashlist.domain.WhitelistEntry;
import com.teecherteamc.platform.hashlist.domain.WhitelistEntryRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 화이트리스트 항목을 해제한다. (DELETE /api/admin/whitelist/{sha256})
 * 행을 지우지 않고 비활성화해서 이력을 남긴다. 이후 그 해시는 다시 일반 검사 경로를 탄다.
 * 복원으로 들어온 항목이라도 격리 상태는 되돌리지 않는다.
 */
@Service
public class RemoveWhitelistEntryUseCase {

    private final WhitelistEntryRepository whitelist;
    private final AuditLogRecorder auditLogRecorder;

    public RemoveWhitelistEntryUseCase(WhitelistEntryRepository whitelist, AuditLogRecorder auditLogRecorder) {
        this.whitelist = whitelist;
        this.auditLogRecorder = auditLogRecorder;
    }

    @Transactional
    public void remove(String sha256, UUID actorId, String ipAddress) {
        HashKey key = HashKey.sha256(sha256);
        WhitelistEntry entry = whitelist.findByKey(key)
                .filter(WhitelistEntry::isActive)
                .orElseThrow(WhitelistNotFoundException::new);

        entry.deactivate();
        whitelist.save(entry);

        // 무엇을 해제했는지 남기려고 마지막 사유를 같이 기록한다.
        auditLogRecorder.record(actorId, AuditAction.REMOVE_WHITELIST, AuditTargetType.WHITELIST,
                WhitelistAuditTarget.of(key), Map.of("reason", entry.getReason()), ipAddress);
    }
}
