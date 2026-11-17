package com.teecherteamc.platform.verdict.application;

import com.teecherteamc.platform.verdict.domain.FingerprintProvenance;
import com.teecherteamc.platform.verdict.domain.NearestSimilarFiles;
import com.teecherteamc.platform.verdict.domain.SimilarFiles;
import com.teecherteamc.platform.verdict.domain.SimilarFingerprintSearchPort;
import com.trendmicro.tlsh.Tlsh;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기준 TLSH와 가까운 지문(유사 파일)의 SHA-256 목록을 찾는다. IMP-12 소급 조회의 "유사 파일 집합 구하기"다.
 * 이 목록으로 기기를 찾고 알리는 일은 호출하는 쪽(IMP-10) 몫이다.
 *
 * <p>설계 결정(2026-11-17): 거리는 Java에서 계산하고, 캐시와 잠금을 쓰지 않으며(판정 경로와 무관),
 * 입력은 sha256과 TLSH 둘 다 받는다. 기준 자신만 제외하고 화이트리스트·블랙리스트 여부는 거르지 않는다.
 * 스캔 대상 출처는 인자로 고르며 기본은 기기가 받은 파일(VERIFIED_UPLOAD)이다.
 * 결과는 가까운 순 최대 limit개이고 더 있으면 truncated다.
 *
 * <p>스캔은 전체 지문을 읽는 O(N) 작업이다. 블랙리스트 등록 같은 쓰기 트랜잭션 <b>안에서</b> 부르지 말고
 * 커밋 뒤에 호출한다(등록 트랜잭션이 코퍼스 배타 잠금을 쥔 동안 스캔하면 모든 CheckHash가 UNKNOWN으로 내려간다).
 * 일괄 가져오기처럼 한 번에 여러 건이 생기는 경로에서 건마다 호출하지 않는다.
 */
// 클래스 전체에 건다. 짧은 오버로드가 긴 오버로드를 this로 부르면 스프링 프록시를 거치지 않아, 메서드마다 걸면
// 짧은 쪽은 트랜잭션 없이 실행되고 어댑터(MANDATORY)가 실패한다.
@Service
@Transactional(readOnly = true)
public class FindSimilarFiles {

    /** 요청 가능한 최대 거리. 팀 측정에서 100 이하이면 같은 계열로 봐도 됐다(Notion TLSH 유사도 검색). */
    public static final int MAX_DISTANCE = 100;
    /** 요청 가능한 최대 결과 수. 호출자가 limit을 크게 불러도 메모리를 묶는다. */
    public static final int HARD_LIMIT = 1000;
    public static final Set<FingerprintProvenance> DEFAULT_PROVENANCES = Set.of(FingerprintProvenance.VERIFIED_UPLOAD);

    private static final Logger log = LoggerFactory.getLogger(FindSimilarFiles.class);
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern TLSH = Pattern.compile("T1[0-9A-F]{70}");

    private final SimilarFingerprintSearchPort port;

    public FindSimilarFiles(SimilarFingerprintSearchPort port) {
        this.port = port;
    }

    /** sha256의 지문을 기준으로 찾는다. 기준 자신은 결과에서 뺀다. 지문이 없으면 NO_REFERENCE_FINGERPRINT. */
    public SimilarFiles bySha256(String sha256, int maxDistance, int limit, Set<FingerprintProvenance> provenances) {
        validate(maxDistance, limit, provenances);
        if (sha256 == null || !SHA256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("sha256은 소문자 16진수 64자여야 합니다.");
        }
        Optional<String> tlsh = port.calculatedTlsh(sha256);
        if (tlsh.isEmpty()) {
            return SimilarFiles.noReferenceFingerprint(sha256, maxDistance);
        }
        return search(sha256, tlsh.get(), maxDistance, limit, provenances, sha256);
    }

    public SimilarFiles bySha256(String sha256, int maxDistance, int limit) {
        return bySha256(sha256, maxDistance, limit, DEFAULT_PROVENANCES);
    }

    /**
     * TLSH를 기준으로 찾는다. excludeSha256은 결과에서 뺄 SHA(없으면 null)이며 정규화된 소문자 값이어야 한다.
     * 해석할 수 없는 TLSH는 예외가 아니라 INVALID_TLSH 상태로 돌려준다.
     */
    public SimilarFiles byTlsh(String tlsh, int maxDistance, int limit, Set<FingerprintProvenance> provenances,
            String excludeSha256) {
        validate(maxDistance, limit, provenances);
        // 어댑터로 넘기기 전에 여기서 걸러낸다. 어댑터가 던진 예외가 이 트랜잭션을 rollback-only로 만드는 것을 피한다.
        if (!parsable(tlsh)) {
            return SimilarFiles.invalidTlsh(maxDistance);
        }
        return search(null, tlsh, maxDistance, limit, provenances, excludeSha256);
    }

    public SimilarFiles byTlsh(String tlsh, int maxDistance, int limit) {
        return byTlsh(tlsh, maxDistance, limit, DEFAULT_PROVENANCES, null);
    }

    private SimilarFiles search(String referenceSha256, String tlsh, int maxDistance, int limit,
            Set<FingerprintProvenance> provenances, String excludeSha256) {
        var collector = new NearestSimilarFiles(maxDistance, limit, excludeSha256);
        long started = System.nanoTime();
        long scanned = port.scan(tlsh, Set.copyOf(provenances), maxDistance, collector::offer);
        var files = collector.files();
        log.info("유사 파일 검색: 기준={}, 최대 거리={}, 출처={}, 스캔 {}건, 결과 {}건{}, {}ms",
                referenceSha256 == null ? "TLSH 직접" : referenceSha256, maxDistance, provenances, scanned,
                files.size(), collector.truncated() ? "(잘림)" : "", (System.nanoTime() - started) / 1_000_000);
        return SimilarFiles.ok(referenceSha256, maxDistance, files, collector.truncated(), scanned);
    }

    private static void validate(int maxDistance, int limit, Set<FingerprintProvenance> provenances) {
        if (maxDistance < 0 || maxDistance > MAX_DISTANCE) {
            throw new IllegalArgumentException("maxDistance는 0~" + MAX_DISTANCE + "이어야 합니다: " + maxDistance);
        }
        if (limit < 1 || limit > HARD_LIMIT) {
            throw new IllegalArgumentException("limit은 1~" + HARD_LIMIT + "이어야 합니다: " + limit);
        }
        if (provenances == null || provenances.isEmpty()) {
            throw new IllegalArgumentException("provenances는 비어 있을 수 없습니다.");
        }
    }

    private static boolean parsable(String tlsh) {
        if (tlsh == null || !TLSH.matcher(tlsh).matches()) {
            return false;
        }
        try {
            Tlsh.fromTlshStr(tlsh);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
