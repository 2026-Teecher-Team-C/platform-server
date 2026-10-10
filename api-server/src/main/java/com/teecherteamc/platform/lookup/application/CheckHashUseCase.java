package com.teecherteamc.platform.lookup.application;

import com.teecherteamc.platform.lookup.domain.BlacklistLookupPort;
import com.teecherteamc.platform.lookup.domain.CachedVerdict;
import com.teecherteamc.platform.lookup.domain.VerdictCacheLookupPort;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Sprint 1 스코프: 블랙리스트 -&gt; file_verdicts 2단계만 본다. 블룸 필터(S2)·화이트리스트(S3)는
 * 아직 이 사슬에 없다. sha256은 이미 정규화·검증된 값으로 받는다(interfaces 레이어가 HashKey로 처리).
 */
@Service
public class CheckHashUseCase {

    private final BlacklistLookupPort blacklistLookupPort;
    private final VerdictCacheLookupPort verdictCacheLookupPort;

    public CheckHashUseCase(BlacklistLookupPort blacklistLookupPort, VerdictCacheLookupPort verdictCacheLookupPort) {
        this.blacklistLookupPort = blacklistLookupPort;
        this.verdictCacheLookupPort = verdictCacheLookupPort;
    }

    public CheckHashResult checkHash(String sha256) {
        Optional<String> blacklistReason = blacklistLookupPort.findActiveReason(sha256);
        if (blacklistReason.isPresent()) {
            return new CheckHashResult("BLOCK", "BLACKLIST", blacklistReason.get());
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
