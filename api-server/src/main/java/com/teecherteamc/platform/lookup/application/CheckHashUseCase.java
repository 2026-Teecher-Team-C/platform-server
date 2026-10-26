package com.teecherteamc.platform.lookup.application;

import com.teecherteamc.platform.lookup.domain.BlacklistLookupPort;
import com.teecherteamc.platform.lookup.domain.BloomFilterPort;
import com.teecherteamc.platform.lookup.domain.CachedVerdict;
import com.teecherteamc.platform.lookup.domain.VerdictCacheLookupPort;
import com.teecherteamc.platform.lookup.domain.WhitelistLookupPort;
import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 조회 사슬: 화이트리스트 -&gt; 블랙리스트 -&gt; 블룸 필터 -&gt; file_verdicts (package-info 참고).
 * 화이트리스트가 맨 앞이라 블랙리스트에도 있는 해시라면 화이트리스트가 이긴다 — 관리자가 명시적으로
 * 예외를 건 것이기 때문이다. sha256은 이미 정규화·검증된 값으로 받는다(interfaces 레이어가 HashKey로 처리).
 * 블룸은 file_verdicts에 한 번이라도 기록된 모든 해시(CLEAN/MALICIOUS 가리지 않고)를 추적하므로, 블룸이
 * "없음"이라고 답하면 그 해시는 확실히 캐시에 없다는 뜻이라 file_verdicts 조회를 안전하게 생략할 수 있다 —
 * CLEAN 캐시 히트를 같이 죽이지 않는다.
 */
@Service
public class CheckHashUseCase {

    private final WhitelistLookupPort whitelistLookupPort;
    private final BlacklistLookupPort blacklistLookupPort;
    private final BloomFilterPort bloomFilterPort;
    private final VerdictCacheLookupPort verdictCacheLookupPort;
    private final Clock clock;

    public CheckHashUseCase(
            WhitelistLookupPort whitelistLookupPort,
            BlacklistLookupPort blacklistLookupPort,
            BloomFilterPort bloomFilterPort,
            VerdictCacheLookupPort verdictCacheLookupPort,
            Clock clock) {
        this.whitelistLookupPort = whitelistLookupPort;
        this.blacklistLookupPort = blacklistLookupPort;
        this.bloomFilterPort = bloomFilterPort;
        this.verdictCacheLookupPort = verdictCacheLookupPort;
        this.clock = clock;
    }

    public CheckHashResult checkHash(String sha256) {
        if (whitelistLookupPort.isEffective(sha256, clock.instant())) {
            return new CheckHashResult("ALLOW", "WHITELIST", null);
        }

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
