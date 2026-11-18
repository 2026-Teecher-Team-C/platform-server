package com.teecherteamc.platform.verdict.application;

import com.teecherteamc.platform.verdict.domain.SimilarityDecisionStore.Thresholds;
import com.teecherteamc.platform.verdict.domain.SimilarityThresholdPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지금 적용 중인 유사도 임계값을 읽는다. (GET /api/admin/similarity-policy)
 * 저장된 값을 허용 범위 검증 없이 그대로 돌려준다. 범위를 좁힌 뒤에도 화면이 저장된 값을 보여 줄 수 있어야 해서다.
 */
@Service
public class GetSimilarityThresholdUseCase {

    private final SimilarityThresholdPort thresholds;

    public GetSimilarityThresholdUseCase(SimilarityThresholdPort thresholds) {
        this.thresholds = thresholds;
    }

    // 포트가 트랜잭션 안에서만 쓰도록 되어 있어서 읽기 전용 트랜잭션을 연다.
    @Transactional(readOnly = true)
    public Thresholds get() {
        return thresholds.current();
    }
}