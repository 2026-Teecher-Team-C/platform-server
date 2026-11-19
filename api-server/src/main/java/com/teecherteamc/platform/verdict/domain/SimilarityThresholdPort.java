package com.teecherteamc.platform.verdict.domain;

import com.teecherteamc.platform.verdict.domain.SimilarityDecisionStore.Thresholds;

/**
 * 관리자가 보고 바꾸는 유사도 임계값의 저장 경계. 판정 경로(SimilarityDecisionStore)와는 따로 둔다.
 * 판정은 검사 트랜잭션 안에서 값을 읽기만 하고, 이 포트는 관리자 요청 한 건이 값을 바꾼다.
 */
public interface SimilarityThresholdPort {

    /** 지금 저장된 값. 허용 범위 검증 없이 있는 그대로 읽는다. */
    Thresholds current();

    /**
     * corpus 배타 락을 잡고 값을 바꾼 뒤, 바꾸기 전 값을 돌려준다.
     * 락을 잡은 뒤에 이전 값을 읽으므로 감사 로그에 남길 "이전 값"이 정확하다.
     * 호출자가 연 트랜잭션 안에서 실행해야 한다. 락이 커밋 때까지 유지돼야 하기 때문이다.
     */
    Thresholds replace(SimilarityThresholds next);
}