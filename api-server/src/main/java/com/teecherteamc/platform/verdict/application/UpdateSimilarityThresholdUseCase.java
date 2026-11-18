package com.teecherteamc.platform.verdict.application;

import com.teecherteamc.platform.audit.application.AuditLogRecorder;
import com.teecherteamc.platform.audit.domain.AuditAction;
import com.teecherteamc.platform.audit.domain.AuditTargetType;
import com.teecherteamc.platform.verdict.domain.SimilarityDecisionStore.Thresholds;
import com.teecherteamc.platform.verdict.domain.SimilarityThresholdPort;
import com.teecherteamc.platform.verdict.domain.SimilarityThresholds;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 유사도 임계값을 바꾼다. (PUT /api/admin/similarity-policy)
 *
 * 순서는 이렇다.
 *  1. 범위 검증: 어기면 InvalidSimilarityThresholdException. 락을 잡기 전에 거른다.
 *  2. corpus 배타 락 아래에서 값 교체: 진행 중인 검사가 끝난 뒤 바뀌고, 다음 검사부터 새 값을 쓴다.
 *     토큰의 정책 문자열이 바뀌므로 캐시된 CLEAN/SIMILARITY 판정은 다음 조회에서 다시 평가된다.
 *  3. 값이 실제로 바뀌었으면 감사 로그를 남긴다.
 */
@Service
public class UpdateSimilarityThresholdUseCase {

    /** 감사 로그 target_id. 값이 하나뿐인 설정이라 고정 문자열이다. */
    private static final String AUDIT_TARGET_ID = "TLSH";

    private final SimilarityThresholdPort thresholds;
    private final AuditLogRecorder auditLogRecorder;

    public UpdateSimilarityThresholdUseCase(SimilarityThresholdPort thresholds, AuditLogRecorder auditLogRecorder) {
        this.thresholds = thresholds;
        this.auditLogRecorder = auditLogRecorder;
    }

    /** 요청 값을 한 덩어리로 묶는다. 범위 검증은 아직 하지 않은 입력 그대로다. */
    public record Command(int blockDistance, int warnDistance, UUID actorId, String ipAddress) {
    }

    @Transactional   // 값 교체와 감사 로그가 한 트랜잭션이라 한쪽이 실패하면 둘 다 취소된다
    public Thresholds update(Command command) {
        // 1. 범위 검증. 락을 잡기 전에 잘못된 입력을 거른다.
        SimilarityThresholds next = new SimilarityThresholds(command.blockDistance(), command.warnDistance());

        // 2. 배타 락 아래에서 교체하고 이전 값을 받는다.
        Thresholds previous = thresholds.replace(next);
        Thresholds current = new Thresholds(next.blockDistance(), next.warnDistance());

        // 3. 같은 값으로 다시 저장한 경우는 기록하지 않는다. 바뀐 게 없는 로그가 쌓이지 않게 한다.
        if (!current.equals(previous)) {
            auditLogRecorder.record(command.actorId(), AuditAction.UPDATE_SIMILARITY_THRESHOLD,
                    AuditTargetType.SIMILARITY_POLICY, AUDIT_TARGET_ID, detail(previous, current), command.ipAddress());
        }
        return current;
    }

    // detail에는 바꾸기 전과 후 값을 모두 남긴다. 나중에 "언제 얼마에서 얼마로 바뀌었나"를 이 로그만으로 알 수 있다.
    private static Map<String, Object> detail(Thresholds previous, Thresholds current) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("previousBlockDistance", previous.blockDistance());
        detail.put("previousWarnDistance", previous.warnDistance());
        detail.put("blockDistance", current.blockDistance());
        detail.put("warnDistance", current.warnDistance());
        return detail;
    }
}