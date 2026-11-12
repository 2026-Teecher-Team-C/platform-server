package com.teecherteamc.platform.verdict.domain;

import java.util.Optional;
import java.util.UUID;

/** 짧은 호출자 트랜잭션 안에서 corpus 일관성, 평가 스냅샷과 요청 연결을 저장하는 포트. */
public interface SimilarityDecisionStore {
    record Assessment(String sample, String tlsh, Integer distance, String classification, String token) {}
    void lockCorpus();
    boolean tryLockCorpus();
    void lockFile(String sha);
    String token();
    boolean cacheValid(String sha);
    Optional<UUID> reusableCleanAnalysis(String sha);
    void bindCached(String sha, UUID event);
    void clearPending(UUID event);
    void clearCache(String sha);
    Assessment compare(String sha);
    void save(String sha, UUID event, UUID analysis, Assessment assessment);
}
