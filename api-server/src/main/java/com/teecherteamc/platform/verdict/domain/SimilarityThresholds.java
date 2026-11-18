package com.teecherteamc.platform.verdict.domain;

/**
 * 관리자가 정하는 TLSH 거리 임계값. 거리가 blockDistance 이하면 차단(BLOCK),
 * warnDistance 이하면 경고(WARN), 그보다 크면 무시(NONE)다.
 *
 * 허용 범위는 여기서 한 번만 검증한다. 차단 상한 50은 IMP-1 결정문이 인용한 오탐률 수치
 * (거리 50에서 0.52%)를 넘기지 않으려고 정한 값이고, 경고는 차단하지 않고 알리기만 하므로 100까지 연다.
 * 범위를 바꿀 때는 이 상수만 고치면 되고 마이그레이션은 필요 없다.
 */
public record SimilarityThresholds(int blockDistance, int warnDistance) {

    public static final int BLOCK_MIN = 0;
    public static final int BLOCK_MAX = 50;
    public static final int WARN_MAX = 100;

    // 레코드의 compact 생성자: 어떤 경로로 만들어도 범위를 벗어난 값은 객체가 되지 못한다.
    public SimilarityThresholds {
        if (blockDistance < BLOCK_MIN || blockDistance > BLOCK_MAX) {
            throw new InvalidSimilarityThresholdException(
                    "차단 임계값은 " + BLOCK_MIN + " 이상 " + BLOCK_MAX + " 이하여야 합니다.");
        }
        // warn > block이면 WARN 구간이 최소 1칸은 있다. DB 체크 제약(block < warn)과 같은 규칙이다.
        if (warnDistance <= blockDistance || warnDistance > WARN_MAX) {
            throw new InvalidSimilarityThresholdException(
                    "경고 임계값은 차단 임계값보다 크고 " + WARN_MAX + " 이하여야 합니다.");
        }
    }
}