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

/** 화이트리스트 항목의 등록 사유만 고친다. (PATCH /api/admin/whitelist/{sha256}) */
@Service
public class UpdateWhitelistReasonUseCase {

    private final WhitelistEntryRepository whitelist;
    private final AuditLogRecorder auditLogRecorder;

    public UpdateWhitelistReasonUseCase(WhitelistEntryRepository whitelist, AuditLogRecorder auditLogRecorder) {
        this.whitelist = whitelist;
        this.auditLogRecorder = auditLogRecorder;
    }

    /** @return 바뀐 항목의 키. 응답은 등록자 username이 필요해서 컨트롤러가 목록 포트로 다시 읽는다 */
    @Transactional
    public HashKey update(String sha256, String reason, UUID actorId, String ipAddress) {
        HashKey key = HashKey.sha256(sha256);
        WhitelistEntry entry = whitelist.findByKey(key)
                .filter(WhitelistEntry::isActive)
                .orElseThrow(WhitelistNotFoundException::new);

        String before = entry.getReason();
        entry.changeReason(reason);
        whitelist.save(entry);

        auditLogRecorder.record(actorId, AuditAction.UPDATE_WHITELIST, AuditTargetType.WHITELIST,
                WhitelistAuditTarget.of(key), Map.of("before", before, "after", reason), ipAddress);
        return key;
    }
}
