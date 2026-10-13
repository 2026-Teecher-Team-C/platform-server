package com.teecherteamc.platform.lookup.application;

import com.teecherteamc.platform.lookup.domain.BlacklistLookupPort;
import com.teecherteamc.platform.lookup.domain.BloomFilterPort;
import com.teecherteamc.platform.lookup.domain.CachedVerdict;
import com.teecherteamc.platform.lookup.domain.VerdictCacheLookupPort;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Sprint 1 스코프였던 블랙리스트 -&gt; file_verdicts 2단계에 블룸 필터(S2)를 끼워 넣었다 —
 * 화이트리스트(S3)는 아직 이 사슬에 없다. sha256은 이미 정규화·검증된 값으로 받는다(interfaces
 * 레이어가 HashKey로 처리). 블룸은 file_verdicts에 한 번이라도 기록된 모든 해시(CLEAN/MALICIOUS
 * 가리지 않고)를 추적하므로, 블룸이 "없음"이라고 답하면 그 해시는 확실히 캐시에 없다는 뜻이라
 * file_verdicts 조회를 안전하게 생략할 수 있다 — CLEAN 캐시 히트를 같이 죽이지 않는다.
 */
@Service
public class CheckHashUseCase {

    private final BlacklistLookupPort blacklistLookupPort;
    private final BloomFilterPort bloomFilterPort;
    private final VerdictCacheLookupPort verdictCacheLookupPort;

    public CheckHashUseCase(
            BlacklistLookupPort blacklistLookupPort,
            BloomFilterPort bloomFilterPort,
            VerdictCacheLookupPort verdictCacheLookupPort) {
        this.blacklistLookupPort = blacklistLookupPort;
        this.bloomFilterPort = bloomFilterPort;
        this.verdictCacheLookupPort = verdictCacheLookupPort;
    }

    public CheckHashResult checkHash(String sha256) {
        Optional<String> blacklistReason = blacklistLookupPort.findActiveReason(sha256);
        if (blacklistReason.isPresent()) {
            return new CheckHashResult("BLOCK", "BLACKLIST", blacklistReason.get());
        }

        if (!bloomFilterPort.mightContain(sha256)) {
            return new CheckHashResult("UNKNOWN", null, null);
        }

        Optional<CachedVerdict> cached = verdictCacheLookupPort.find(sha256);
        if (cached.isPresent() && !cached.get().stale()) {
            return fromCachedVerdict(cached.get().verdict());
        }

        return new CheckHashResult("UNKNOWN", null, null);
    }

    private CheckHashResult fromCachedVerdict(String verdict) {
        return switch (verdict) {
            case "CLEAN" -> new CheckHashResult("ALLOW", "CACHE", null);
            case "MALICIOUS", "SUSPICIOUS" -> new CheckHashResult("BLOCK", "CACHE", "cached verdict: " + verdict);
            // UNKNOWN/ERROR는 과거 분석이 결론을 못 낸 것 — 캐시 미스로 취급해 재검사를 유도한다.
            default -> new CheckHashResult("UNKNOWN", null, null);
        };
    }
}
