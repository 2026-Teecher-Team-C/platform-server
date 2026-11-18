package com.teecherteamc.platform.verdict.domain;

/**
 * 관리자가 정하는 유사도(TLSH) 임계값. 값을 만드는 순간 허용 범위를 검사한다.
 *
 * <ul>
 *   <li>차단(BLOCK): 거리 ≤ blockDistance</li>
 *   <li>경고(WARN): blockDistance &lt; 거리 ≤ warnDistance (알림만, 차단하지 않는다)</li>
 *   <li>그 위는 무시(NONE)</li>
 * </ul>
 *
 * 경고 상한(WARN_MAX)이 70인 이유: L-value 인덱스(V19)가 입력 길이 구간 ±5 후보만 비교한다.
 * TLSH 길이 페널티는 L 차이 2 이상이면 차이×12라 6칸(72)부터 70을 넘기 때문에, 경고 상한이
 * 70을 넘으면 인덱스가 걸러낸 후보 중에 경고 대상이 생겨 판정이 조용히 달라진다.
 * 이 값을 올리려면 인덱스의 창 크기(TlshLValue.WINDOW)를 먼저 넓혀야 한다.
 * 둘이 어긋나지 않는지는 SimilarityThresholdWindowGuardTest가 막는다.
 */
public record SimilarityThresholds(int blockDistance, int warnDistance) {

    public static final int BLOCK_MIN = 0;
    public static final int BLOCK_MAX = 50;
    public static final int WARN_MAX = 70;

    public SimilarityThresholds {
        if (blockDistance < BLOCK_MIN || blockDistance > BLOCK_MAX) {
            throw new InvalidSimilarityThresholdException(
                    "차단 임계값은 " + BLOCK_MIN + " 이상 " + BLOCK_MAX + " 이하여야 합니다.");
        }
        // 경고가 차단과 같으면 WARN 구간이 한 칸도 없어서 거부한다.
        if (warnDistance <= blockDistance || warnDistance > WARN_MAX) {
            throw new InvalidSimilarityThresholdException(
                    "경고 임계값은 차단 임계값보다 크고 " + WARN_MAX + " 이하여야 합니다.");
        }
    }
}