package com.teecherteamc.platform.appeal.application;

import com.teecherteamc.platform.appeal.domain.Appeal;
import com.teecherteamc.platform.appeal.domain.AppealApproved;
import com.teecherteamc.platform.appeal.domain.AppealRepository;
import com.teecherteamc.platform.audit.application.AuditLogRecorder;
import com.teecherteamc.platform.audit.domain.AuditAction;
import com.teecherteamc.platform.audit.domain.AuditTargetType;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자가 소명을 승인·거절한다. (POST /api/admin/appeals/{appealId}/approve|reject)
 *
 * <p>소명 행을 SELECT … FOR UPDATE로 잡고 PENDING일 때만 바꾼다. 동시에 결정하면 늦은 쪽은 앞 트랜잭션이 끝난 뒤
 * 결정된 행을 읽고 {@link AppealAlreadyDecidedException}(409)이 된다.
 *
 * <p>승인은 {@link AppealApproved}를 발행한다. hashlist의 동기 리스너가 같은 트랜잭션 안에서 화이트리스트
 * 등록(또는 재활성화)과 블랙리스트 해제를 처리하므로, 소명 상태와 화이트리스트가 함께 커밋되거나 함께 취소된다.
 */
@Service
public class DecideAppealUseCase {

    static final int MAX_NOTE_CODE_POINTS = 1000;
    static final String DEFAULT_WHITELIST_REASON = "소명 승인";

    private final AppealRepository appealRepository;
    private final GetAppealDetailUseCase getAppealDetailUseCase;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditLogRecorder auditLogRecorder;
    private final Clock clock;

    public DecideAppealUseCase(AppealRepository appealRepository, GetAppealDetailUseCase getAppealDetailUseCase,
                               ApplicationEventPublisher eventPublisher, AuditLogRecorder auditLogRecorder,
                               Clock clock) {
        this.appealRepository = appealRepository;
        this.getAppealDetailUseCase = getAppealDetailUseCase;
        this.eventPublisher = eventPublisher;
        this.auditLogRecorder = auditLogRecorder;
        this.clock = clock;
    }

    /** 승인한다. note는 선택이며, 비었으면 화이트리스트 사유는 "소명 승인"이다. 없는 소명이면 AppealNotFoundException. */
    @Transactional
    public AdminAppealDetail approve(UUID appealId, String note, UUID adminId, String ipAddress) {
        String validNote = isBlank(note) ? null : validNote(note);
        Appeal appeal = lockPending(appealId);
        Instant now = clock.instant();
        appeal.approve(adminId, validNote, now);

        // 화이트리스트 등록·블랙리스트 해제는 hashlist 리스너가 이 트랜잭션 안에서 동기로 처리한다
        eventPublisher.publishEvent(new AppealApproved(appealId, appeal.getSha256(),
                validNote == null ? DEFAULT_WHITELIST_REASON : validNote, adminId, ipAddress));

        auditLogRecorder.record(adminId, AuditAction.APPROVE_APPEAL, AuditTargetType.APPEAL, appealId.toString(),
                auditDetail(appeal), ipAddress);
        return saveAndDetail(appeal);
    }

    /** 거절한다. note는 필수다. 화이트리스트·블랙리스트는 건드리지 않는다. */
    @Transactional
    public AdminAppealDetail reject(UUID appealId, String note, UUID adminId, String ipAddress) {
        String validNote = validNote(note);
        Appeal appeal = lockPending(appealId);
        appeal.reject(adminId, validNote, clock.instant());

        auditLogRecorder.record(adminId, AuditAction.REJECT_APPEAL, AuditTargetType.APPEAL, appealId.toString(),
                auditDetail(appeal), ipAddress);
        return saveAndDetail(appeal);
    }

    private Appeal lockPending(UUID appealId) {
        Appeal appeal = appealRepository.findByIdForUpdate(appealId).orElseThrow(AppealNotFoundException::new);
        if (!appeal.isPending()) {
            throw new AppealAlreadyDecidedException();
        }
        return appeal;
    }

    private AdminAppealDetail saveAndDetail(Appeal appeal) {
        // flush까지 해야 상세 조회(JDBC)가 이 트랜잭션의 변경(소명 상태·블랙리스트 해제)을 본다
        appealRepository.save(appeal);
        return getAppealDetailUseCase.get(appeal.getAppealId()).orElseThrow(AppealNotFoundException::new);
    }

    private static Map<String, Object> auditDetail(Appeal appeal) {
        // 승인 의견은 없을 수 있다(null). Map.of는 null 값을 받지 않는다.
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("sha256", appeal.getSha256());
        detail.put("note", appeal.getDecisionNote());
        return detail;
    }

    private static boolean isBlank(String note) {
        return note == null || note.isBlank();
    }

    private static String validNote(String note) {
        String stripped = note == null ? "" : note.strip();
        int length = stripped.codePointCount(0, stripped.length());
        // PostgreSQL TEXT는 NUL을 저장하지 못해 500이 되므로 여기서 막는다.
        if (stripped.indexOf('\u0000') >= 0 || length < 1 || length > MAX_NOTE_CODE_POINTS) {
            throw new InvalidDecisionNoteException("의견은 1자 이상 " + MAX_NOTE_CODE_POINTS + "자 이하로 써 주세요.");
        }
        return stripped;
    }
}
