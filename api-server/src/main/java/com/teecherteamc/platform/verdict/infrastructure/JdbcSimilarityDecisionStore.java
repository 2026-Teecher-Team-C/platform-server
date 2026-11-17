package com.teecherteamc.platform.verdict.infrastructure;

import com.teecherteamc.platform.verdict.domain.SimilarityDecisionStore;
import com.teecherteamc.platform.verdict.application.SimilarityDecisions;

import com.trendmicro.tlsh.Tlsh;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * YARA 통과 후의 보조 정책과 그 근거 저장 경계. 업로드 지문 계산과는 별개다.
 * 호출자가 연 짧은 트랜잭션 안에서만 사용한다. 잠금만 잡고 자동 커밋되면 비교/저장을 보호하지 못한다.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcSimilarityDecisionStore implements SimilarityDecisionStore {
    private final JdbcTemplate jdbc;
    private final ParsedSimilarityCorpus corpus;

    /** 비교 대상 상한(시나리오 B 규모별 측정). 0 이하는 무제한. 바꾸면 재시작한다. */
    private final int corpusLimit;

    /**
     * indexed(기본): 입력 L-value ±5 후보만 읽는다(V19 인덱스, 결론은 전체 비교와 같다 — TlshLValue 참고).
     * bruteforce: 후보 전체를 읽는다. 동일성 검사(고도화 테스트 시나리오 C)와 되돌리기용이다.
     */
    private final boolean indexed;
    private static final java.util.regex.Pattern T1_FORMAT = java.util.regex.Pattern.compile("T1[0-9A-F]{70}");

    public JdbcSimilarityDecisionStore(JdbcTemplate jdbc, ParsedSimilarityCorpus corpus,
            @Value("${similarity.corpus.limit:0}") int corpusLimit,
            @Value("${similarity.mode:indexed}") String mode) {
        this.jdbc = jdbc;
        this.corpus = corpus;
        this.corpusLimit = corpusLimit;
        this.indexed = switch (mode) {
            case "indexed" -> true;
            case "bruteforce" -> false;
            default -> throw new IllegalArgumentException("similarity.mode는 indexed 또는 bruteforce: " + mode);
        };
    }

    /** V8의 목록/지문 쓰기 트리거는 같은 키의 배타 잠금을 얻는다. 비교에서 커밋까지 후보가 고정된다. */
    public void lockCorpus() {
        jdbc.execute("SELECT pg_advisory_xact_lock_shared(similarity_lock_key('corpus')::bigint)");
    }

    /** try-lock 실패는 SQL 오류가 아니므로 트랜잭션을 중단하지 않고 UNKNOWN으로 내려갈 수 있다. */
    public boolean tryLockCorpus() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock_shared(similarity_lock_key('corpus')::bigint)", Boolean.class));
    }

    /** corpus -> 파일 순서로 획득한다. 새 SHA 동시 업로드도 행이 생기기 전부터 직렬화한다. */
    public void lockFile(String sha) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(similarity_lock_key('file'), hashtext(?))", sha);
    }

    public String token() {
        return jdbc.queryForObject("SELECT token FROM current_similarity_corpus_token", String.class);
    }

    /** revision뿐 아니라 시간으로 만료된 화이트리스트 수와 정책 버전도 토큰에 포함한다. */
    public boolean cacheValid(String sha) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM similarity_cache c JOIN similarity_assessments a USING(assessment_id)
                    WHERE c.sha256=? AND a.corpus_token=(SELECT token FROM current_similarity_corpus_token))
                """, Boolean.class, sha));
    }

    /** 최근 엔진 결과가 실제 성공 CLEAN일 때만 corpus 재평가에 재사용한다. */
    public java.util.Optional<UUID> reusableCleanAnalysis(String sha) {
        return jdbc.queryForList("""
                SELECT analysis_id FROM (SELECT analysis_id, status, verdict FROM analyses
                    WHERE sha256=? ORDER BY analyzed_at DESC LIMIT 1) latest
                WHERE status='SUCCESS' AND verdict='CLEAN'
                """, UUID.class, sha).stream().findFirst();
    }

    /** 캐시는 새 평가를 꾸미지 않고 실제 사용한 기존 근거를 그 요청 UUID에 연결한다. */
    public void bindCached(String sha, UUID event) {
        if (event != null) {
            jdbc.update("""
                    UPDATE event_decision_evidence e SET assessment_id=(SELECT assessment_id FROM similarity_cache WHERE sha256=?)
                    WHERE e.event_id=? AND e.sha256=?
                      AND NOT EXISTS(SELECT 1 FROM download_events d WHERE d.event_id=e.event_id)
                    """, sha, event, sha);
        }
    }

    /** 평가 없는 최종 응답은 미보고 포인터만 비운다. 소유권과 보고된 과거 근거는 보존한다. */
    public void clearPending(UUID event) {
        if (event != null) jdbc.update("""
                UPDATE event_decision_evidence e SET assessment_id=NULL WHERE event_id=?
                  AND NOT EXISTS(SELECT 1 FROM download_events d WHERE d.event_id=e.event_id)
                """, event);
    }

    /** 새 YARA 차단/실패에는 이전 WARN/BLOCK 평가를 재사용하면 안 된다. 과거 이벤트 근거는 삭제하지 않는다. */
    public void clearCache(String sha) {
        jdbc.update("DELETE FROM similarity_cache WHERE sha256=?", sha);
    }

    public Assessment compare(String sha) {
        List<String> values = jdbc.queryForList("""
                SELECT tlsh FROM file_fingerprints WHERE sha256=? AND calculation_status='CALCULATED'
                """, String.class, sha);
        if (values.isEmpty()) {
            return new Assessment(null, null, null, "UNAVAILABLE", token());
        }
        return compareTlsh(values.getFirst());
    }

    /** 검증된 지문이든 에이전트가 보낸 문자열이든 같은 후보 corpus와 같은 동률 규칙으로 비교한다. */
    public Assessment compareTlsh(String claimed) {
        String token = token();
        Tlsh input;
        try {
            input = Tlsh.fromTlshStr(claimed);
        } catch (RuntimeException e) {
            return new Assessment(null, null, null, "UNAVAILABLE", token);
        }
        Assessment[] best = {new Assessment(null, null, null, "NONE", token)};
        // PostgreSQL 커서로 전 후보를 읽는다. fetchSize는 전송 묶음이지 후보 수 상한이 아니다.
        // 거리 동률이면 SHA가 작은 샘플을 선택하여 실행 순서나 DB 계획이 달라도 근거가 같다.
        // 서버·에이전트 지문은 모두 "T1" + 70자다(V8 CHECK, CheckHashQuery.claimedTlsh). 다른 표기면 전체를 비교한다.
        Integer[] window = indexed && T1_FORMAT.matcher(claimed).matches()
                ? TlshLValue.window(TlshLValue.of(claimed)) : null;
        // 창마다 후보 집합이 다르므로 캐시 키에 넣는다(같은 토큰의 다른 창 스냅샷을 재사용하면 안 된다).
        String cacheKey = window == null ? token : token + "|L" + window[TlshLValue.WINDOW];
        corpus.visit(cacheKey, accept -> jdbc.query(connection -> {
            String sql = "SELECT sha256, tlsh FROM active_malicious_fingerprints"
                    + (window == null ? "" : " WHERE tlsh_lvalue(tlsh) = ANY(?)")
                    + (corpusLimit > 0 ? " LIMIT ?" : "");
            PreparedStatement statement = connection.prepareStatement(sql);
            int index = 1;
            if (window != null) statement.setArray(index++, connection.createArrayOf("integer", window));
            if (corpusLimit > 0) statement.setInt(index, corpusLimit);
            statement.setFetchSize(256);
            return statement;
        }, (RowCallbackHandler) rs -> accept.accept(new ParsedSimilarityCorpus.Candidate(
                rs.getString(1), rs.getString(2), Tlsh.fromTlshStr(rs.getString(2))))), candidate -> {
            String hash = candidate.sha();
            String tlsh = candidate.encoded();
            int distance = input.totalDiff(candidate.parsed(), true);
            Assessment current = best[0];
            if (current.distance() == null || distance < current.distance()
                    || (distance == current.distance() && hash.compareTo(current.sample()) < 0)) {
                best[0] = new Assessment(hash, tlsh, distance, "NONE", token);
            }
        });
        return best[0];
    }

    /** 원본 엔진 analysis와 최종 정책을 분리한다. 기준 샘플이 해제돼도 당시 TLSH/거리 스냅샷은 남는다. */
    public void save(String sha, UUID event, UUID analysis, Assessment assessment) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO similarity_assessments(assessment_id, sha256, analysis_id, corpus_token, policy_version,
                    matched_sample_sha256, matched_sample_tlsh, distance, classification)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, sha, analysis, assessment.token(), SimilarityDecisions.POLICY, assessment.sample(), assessment.tlsh(),
                assessment.distance(), assessment.classification());
        jdbc.update("""
                INSERT INTO similarity_cache(sha256, assessment_id) VALUES (?, ?)
                ON CONFLICT (sha256) DO UPDATE SET assessment_id=EXCLUDED.assessment_id
                """, sha, id);
        if (event != null) {
            jdbc.update("""
                    UPDATE event_decision_evidence e SET assessment_id=? WHERE event_id=? AND sha256=?
                      AND NOT EXISTS(SELECT 1 FROM download_events d WHERE d.event_id=e.event_id)
                    """, id, event, sha);
        }
    }

    /**
     * 에이전트 주장 근거. 검증되지 않았으므로 캐시/판정 테이블은 건드리지 않고, 아직 보고되지 않은 요청에만 연결한다.
     * 연결할 요청이 없으면 매달린 평가 행을 남기지 않는다.
     */
    public void saveClaimed(String sha, UUID event, Assessment assessment) {
        if (event == null) return;
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO similarity_assessments(assessment_id, sha256, analysis_id, corpus_token, policy_version,
                    matched_sample_sha256, matched_sample_tlsh, distance, classification, input_source)
                VALUES (?, ?, NULL, ?, ?, ?, ?, ?, ?, 'AGENT_CLAIMED')
                """, id, sha, assessment.token(), SimilarityDecisions.POLICY, assessment.sample(), assessment.tlsh(),
                assessment.distance(), assessment.classification());
        int linked = jdbc.update("""
                UPDATE event_decision_evidence e SET assessment_id=? WHERE event_id=? AND sha256=?
                  AND NOT EXISTS(SELECT 1 FROM download_events d WHERE d.event_id=e.event_id)
                """, id, event, sha);
        if (linked == 0) jdbc.update("DELETE FROM similarity_assessments WHERE assessment_id=?", id);
    }
}
