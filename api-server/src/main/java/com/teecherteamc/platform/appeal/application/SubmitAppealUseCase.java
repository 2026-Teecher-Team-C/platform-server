package com.teecherteamc.platform.appeal.application;

import com.teecherteamc.platform.appeal.domain.Appeal;
import com.teecherteamc.platform.appeal.domain.AppealClaims;
import com.teecherteamc.platform.appeal.domain.AppealRepository;
import com.teecherteamc.platform.appeal.domain.FindBlockedDownloadPort;
import com.teecherteamc.platform.audit.application.AuditLogRecorder;
import com.teecherteamc.platform.audit.domain.AuditAction;
import com.teecherteamc.platform.audit.domain.AuditTargetType;
import java.time.Clock;
import java.util.Map;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 직원이 소명을 제출한다. 이벤트 1건당 1건이다. 동시에 두 번 보내도 uq_appeals_event가 하나만 남기고,
 * 진 쪽은 AppealAlreadySubmittedException이 된다.
 */
@Service
public class SubmitAppealUseCase {

    static final int MAX_REASON_CODE_POINTS = 1000;
    private static final String UNIQUE_EVENT_CONSTRAINT = "uq_appeals_event";

    private final AppealLinks appealLinks;
    private final AppealRepository appealRepository;
    private final FindBlockedDownloadPort findBlockedDownload;
    private final AuditLogRecorder auditLogRecorder;
    private final Clock clock;

    public SubmitAppealUseCase(AppealLinks appealLinks, AppealRepository appealRepository,
                               FindBlockedDownloadPort findBlockedDownload, AuditLogRecorder auditLogRecorder,
                               Clock clock) {
        this.appealLinks = appealLinks;
        this.appealRepository = appealRepository;
        this.findBlockedDownload = findBlockedDownload;
        this.auditLogRecorder = auditLogRecorder;
        this.clock = clock;
    }

    @Transactional
    public AppealView submit(String token, String reason, String ipAddress) {
        AppealClaims claims = appealLinks.tokens().verify(token).orElseThrow(AppealNotFoundException::new);
        String stripped = validReason(reason);
        Appeal existing = appealRepository.findByEventId(claims.eventId()).orElse(null);
        if (existing != null) {
            // 다른 사람의 소명이면 409로 존재를 알리지 않고 404로 숨긴다
            if (!existing.isOwnedBy(claims.agentId(), claims.sha256())) {
                throw new AppealNotFoundException();
            }
            throw new AppealAlreadySubmittedException();
        }
        Appeal appeal;
        try {
            appeal = appealRepository.saveNew(
                    Appeal.submit(claims.eventId(), claims.sha256(), claims.agentId(), stripped, clock.instant()));
        } catch (DataIntegrityViolationException e) {
            // 사전 조회 뒤에 다른 요청이 먼저 넣었다. FK 위반(없는 agent) 같은 다른 위반은 409로 숨기지 않는다.
            if (!isDuplicateEvent(e)) {
                throw e;
            }
            throw new AppealAlreadySubmittedException(e);
        }
        // 직원은 관리자가 아니므로 actor_id는 null. 사유는 detail에 넣지 않는다(appeals 행에 있다).
        auditLogRecorder.record(null, AuditAction.APPEAL_SUBMITTED, AuditTargetType.APPEAL,
                appeal.getAppealId().toString(), Map.of(), ipAddress);
        return AppealView.of(claims, findBlockedDownload.find(claims.eventId(), claims.agentId()).orElse(null),
                appeal);
    }

    private static boolean isDuplicateEvent(DataIntegrityViolationException e) {
        String message = NestedExceptionUtils.getMostSpecificCause(e).getMessage();
        return message != null && message.contains(UNIQUE_EVENT_CONSTRAINT);
    }

    private static String validReason(String reason) {
        String stripped = reason == null ? "" : reason.strip();
        int length = stripped.codePointCount(0, stripped.length());
        // PostgreSQL TEXT는 NUL을 저장하지 못해 500이 되므로 여기서 막는다.
        if (stripped.indexOf('\u0000') >= 0 || length < 1 || length > MAX_REASON_CODE_POINTS) {
            throw new InvalidAppealReasonException("소명 사유는 1자 이상 " + MAX_REASON_CODE_POINTS + "자 이하로 써 주세요.");
        }
        return stripped;
    }
}
