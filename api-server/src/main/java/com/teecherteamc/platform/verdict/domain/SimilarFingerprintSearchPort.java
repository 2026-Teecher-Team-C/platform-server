package com.teecherteamc.platform.verdict.domain;

import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * file_fingerprints에서 기준 TLSH와 가까운 지문을 찾는 읽기 전용 포트.
 * 호출자가 연 트랜잭션 안에서만 쓴다(커서로 나눠 읽으려면 자동 커밋이 꺼져 있어야 한다).
 * 잠금을 잡지 않으며 판정 경로의 코퍼스 캐시(ParsedSimilarityCorpus)와 무관하다.
 */
public interface SimilarFingerprintSearchPort {

    /** sha256의 계산된(CALCULATED) 지문. 지문이 없거나 TOO_SHORT 등 계산 불가면 비어 있다. */
    Optional<String> calculatedTlsh(String sha256);

    /**
     * 지정한 출처의 계산된 지문을 모두 읽어 referenceTlsh와의 거리를 계산하고, maxDistance 이하만 sink에 넘긴다.
     * 순서는 보장하지 않는다. 비교할 수 없는 행은 건너뛰고 경고 로그만 남긴다.
     *
     * @return 거리를 계산한 행 수(운영에서 스캔 비용을 재는 값)
     * @throws IllegalArgumentException referenceTlsh를 해석할 수 없거나 provenances가 비어 있을 때
     */
    long scan(String referenceTlsh, Set<FingerprintProvenance> provenances, int maxDistance,
            BiConsumer<String, Integer> sink);
}
