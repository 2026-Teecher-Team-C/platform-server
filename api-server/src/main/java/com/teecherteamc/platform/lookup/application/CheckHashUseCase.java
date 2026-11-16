package com.teecherteamc.platform.lookup.application;

import com.teecherteamc.platform.lookup.domain.BlacklistLookupPort;
import com.teecherteamc.platform.lookup.domain.BloomFilterPort;
import com.teecherteamc.platform.lookup.domain.CachedVerdict;
import com.teecherteamc.platform.lookup.domain.VerdictCacheLookupPort;
import com.teecherteamc.platform.lookup.domain.WhitelistLookupPort;
import com.teecherteamc.platform.verdict.application.EventDecisionEvidence;
import com.teecherteamc.platform.verdict.application.SimilarityDecisions;
import com.teecherteamc.platform.verdict.application.SubmitFileResult;
import com.teecherteamc.platform.verdict.domain.SimilarityDecisionStore.Assessment;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 조회 사슬: 블룸 필터 -&gt; (hit) 화이트리스트 -&gt; 블랙리스트 -&gt; file_verdicts -&gt; (miss·미결) 에이전트 TLSH 비교
 * (package-info 참고). sha256은 이미 정규화·검증된 값으로 받는다(interfaces 레이어가 HashKey로 처리).
 *
 * <p>블룸은 file_verdicts에 기록된 모든 해시와 블랙·화이트리스트의 모든 해시를 담는다(목록 쓰기 커밋 뒤 add,
 * 재구축 뒤 목록 재반영). 그래서 블룸이 "없음"이면 목록과 캐시 조회를 안전하게 생략할 수 있다. 블룸 hit이면
 * 화이트리스트가 먼저라 블랙리스트에도 있는 해시는 화이트리스트가 이긴다 — 관리자가 명시적으로 예외를 건 것이다.
 *
 * <p>정확 일치·캐시로 결론이 안 나면 에이전트가 보낸 TLSH를 활성 악성 기준과 비교한다. ≤30이면 업로드 없이
 * BLOCK/SIMILARITY, 31~70이면 근거만 남기고 UNKNOWN(업로드·YARA로 계속), 그 밖은 저장 없이 UNKNOWN이다.
 * 이 TLSH는 검증되지 않은 주장이므로 결과를 file_verdicts·similarity_cache에 쓰지 않고 그 이벤트의 근거로만
 * 남긴다 — 다른 에이전트의 같은 SHA 다운로드를 오염시키지 않는다.
 *
 * <p>같은 이벤트의 SubmitFile이 진행 중이면 CheckHash는 이벤트 advisory lock을 시간 제한 없이 기다린다.
 * 이 경우 에이전트 RPC 타임아웃이 먼저 걸려 fail-close(차단)된다.
 */
@Service
public class CheckHashUseCase {

    private static final Logger log = LoggerFactory.getLogger(CheckHashUseCase.class);
    private static final CheckHashResult UNKNOWN = new CheckHashResult("UNKNOWN", null, null);

    private final WhitelistLookupPort whitelistLookupPort;
    private final BlacklistLookupPort blacklistLookupPort;
    private final BloomFilterPort bloomFilterPort;
    private final VerdictCacheLookupPort verdictCacheLookupPort;
    private final Clock clock;
    private final SimilarityDecisions similarity;
    private final EventDecisionEvidence evidence;

    public CheckHashUseCase(
            WhitelistLookupPort whitelistLookupPort,
            BlacklistLookupPort blacklistLookupPort,
            BloomFilterPort bloomFilterPort,
            VerdictCacheLookupPort verdictCacheLookupPort,
            Clock clock, SimilarityDecisions similarity, EventDecisionEvidence evidence) {
        this.whitelistLookupPort = whitelistLookupPort;
        this.blacklistLookupPort = blacklistLookupPort;
        this.bloomFilterPort = bloomFilterPort;
        this.verdictCacheLookupPort = verdictCacheLookupPort;
        this.clock = clock;
        this.similarity = similarity;
        this.evidence = evidence;
    }

    @Transactional
    public CheckHashResult checkHash(CheckHashQuery query) {
        String sha256 = query.sha256();
        // 근거를 남길 수 있는 요청(TLSH + event_id)만 이벤트 소유권을 먼저 잡는다. 잠금 순서를 SubmitFile과 같은
        // event -> corpus로 맞추기 위해서다. event 잠금은 아무 잠금도 쥐지 않은 채 기다리고, 그 뒤 corpus는
        // try-lock이라 기다리지 않으므로 이 트랜잭션은 대기 사이클에 들어가지 않는다. 반대로 corpus를 쥔 채
        // event 잠금을 기다리면 [CheckHash: corpus 공유 보유 -> event 대기] / [SubmitFile: event 보유 -> 대기 중인
        // corpus 배타 요청 뒤에서 corpus 공유 대기] / [corpus 작성자: CheckHash의 공유 해제 대기] 사이클이 생긴다.
        boolean claimable = query.tlsh() != null && query.eventId() != null;
        if (claimable) evidence.claim(query.eventId(), sha256, query.agentId());

        // 정확 예외, 후보 토큰과 캐시를 목록 쓰기와 한 경계에서 읽는다.
        // 쓰기와 경쟁하면 캐시 ALLOW를 사용하지 않는다. 잠금 대기 대신 업로드 재검사를 유도한다.
        if (!similarity.tryLockCorpus()) return UNKNOWN;

        boolean listsChecked = false;
        if (bloomMightContain(sha256)) {
            if (whitelistLookupPort.isEffective(sha256, clock.instant())) {
                return new CheckHashResult("ALLOW", "WHITELIST", null);
            }
            Optional<String> blacklistReason = blacklistLookupPort.findActiveReason(sha256);
            if (blacklistReason.isPresent()) {
                return new CheckHashResult("BLOCK", "BLACKLIST", blacklistReason.get());
            }
            listsChecked = true;
            CheckHashResult cached = fromCache(sha256);
            if (cached != null) return cached;
        }

        if (query.tlsh() == null) return UNKNOWN;
        Assessment assessment = similarity.compareClaimed(query.tlsh());
        String classification = assessment.classification();
        if (!"BLOCK".equals(classification) && !"WARN".equals(classification)) return UNKNOWN;
        if (!listsChecked) {
            // 블룸이 목록 해시를 놓쳐도(예: Redis 초기화 뒤 재구축 전) TLSH가 정확 목록을 이기지 않게, 저장·차단
            // 직전에만 정확 목록을 한 번 더 본다. 블룸 miss의 대부분(NONE·TLSH 없음)은 이 조회를 하지 않는다.
            SubmitFileResult exact = similarity.exactOverride(sha256);
            if (exact != null) return new CheckHashResult(exact.decision(), exact.source(), exact.reason());
        }
        // claim()을 이미 같은 sha·agent로 했으므로 연결 대상 evidence 행이 있다. event_id가 없는 구형 요청은
        // 붙일 이벤트가 없어 저장하지 않는다(판정만 돌려준다).
        if (claimable) similarity.saveClaimed(sha256, query.eventId(), assessment);
        return "BLOCK".equals(classification)
                ? new CheckHashResult("BLOCK", "SIMILARITY", "TLSH distance: " + assessment.distance())
                : UNKNOWN;
    }

    /**
     * Redis 장애로 블룸이 던지면 "있을 수 있음"으로 본다. 목록·캐시 조회를 더 할 뿐 ALLOW 쪽으로 기울지 않아,
     * 블룸 장애가 화이트리스트 다운로드를 막지 않는다(목록 판정은 Postgres만으로 가능).
     */
    private boolean bloomMightContain(String sha256) {
        try {
            return bloomFilterPort.mightContain(sha256);
        } catch (RuntimeException e) {
            log.warn("블룸 필터 조회 실패 — 목록·캐시 조회로 계속한다", e);
            return true;
        }
    }

    /** 결론 있는 캐시면 결과, 미결(없음·stale·UNKNOWN/ERROR)이면 null. */
    private CheckHashResult fromCache(String sha256) {
        Optional<CachedVerdict> cached = verdictCacheLookupPort.find(sha256);
        if (cached.isEmpty() || cached.get().stale()) return null;
        if ("SIMILARITY".equals(cached.get().source())) {
            return new CheckHashResult("BLOCK", "SIMILARITY", "cached similarity verdict");
        }
        return switch (cached.get().verdict()) {
            case "CLEAN" -> new CheckHashResult("ALLOW", "CACHE", null);
            case "MALICIOUS", "SUSPICIOUS" ->
                    new CheckHashResult("BLOCK", "CACHE", "cached verdict: " + cached.get().verdict());
            // UNKNOWN/ERROR는 과거 분석이 결론을 못 낸 것 — 캐시 미스로 취급해 재검사를 유도한다.
            default -> null;
        };
    }
}
