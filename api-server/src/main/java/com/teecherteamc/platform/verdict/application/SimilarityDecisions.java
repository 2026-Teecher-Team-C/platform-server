package com.teecherteamc.platform.verdict.application;

import com.teecherteamc.platform.lookup.domain.BlacklistLookupPort;
import com.teecherteamc.platform.lookup.domain.WhitelistLookupPort;
import com.teecherteamc.platform.verdict.domain.SimilarityDecisionStore;
import com.teecherteamc.platform.verdict.domain.SimilarityDecisionStore.Assessment;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** YARA 통과 뒤 보조 정책. DB 잠금/조회는 포트에 위임하고 정확 SHA 예외는 기존 조회 계약을 재사용한다. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class SimilarityDecisions {
    public static final String POLICY = "tlsh-30-70-v1";
    private final SimilarityDecisionStore store;
    private final WhitelistLookupPort whitelist;
    private final BlacklistLookupPort blacklist;
    private final Clock clock;

    public SimilarityDecisions(SimilarityDecisionStore store,
            WhitelistLookupPort whitelist, BlacklistLookupPort blacklist, Clock clock) {
        this.store = store;
        this.whitelist = whitelist;
        this.blacklist = blacklist;
        this.clock = clock;
    }

    /** 정확 SHA 예외만 허용을 만들 수 있다. TLSH 유사 신호는 기존 악성 차단을 해제하지 않는다. */
    public SubmitFileResult exactOverride(String sha) {
        if (whitelist.isEffective(sha, clock.instant())) return new SubmitFileResult("ALLOW", "WHITELIST", null, List.of());
        return blacklist.findActiveReason(sha).map(reason -> new SubmitFileResult("BLOCK", "BLACKLIST", reason, List.of())).orElse(null);
    }
    public void lockCorpus() { store.lockCorpus(); }
    public boolean tryLockCorpus() { return store.tryLockCorpus(); }
    public void lockFile(String sha) { store.lockFile(sha); }
    public String token() { return store.token(); }
    public boolean cacheValid(String sha) { return store.cacheValid(sha); }
    public Optional<UUID> reusableCleanAnalysis(String sha) { return store.reusableCleanAnalysis(sha); }
    public void bindCached(String sha, UUID event) { store.bindCached(sha, event); }
    public void clearPending(UUID event) { store.clearPending(event); }
    public void clearCache(String sha) { store.clearCache(sha); }
    /** 거리 경계는 보조 정책이며 WARN을 차단 값 SUSPICIOUS로 바꾸지 않는다. */
    public Assessment compare(String sha) {
        Assessment nearest = store.compare(sha);
        if (nearest.distance() == null) return nearest;
        String classification = nearest.distance() <= 30 ? "BLOCK" : nearest.distance() <= 70 ? "WARN" : "NONE";
        return new Assessment(nearest.sample(), nearest.tlsh(), nearest.distance(), classification, nearest.token());
    }
    public void save(String sha, UUID event, UUID analysis, Assessment assessment) { store.save(sha, event, analysis, assessment); }
}
