package com.teecherteamc.platform.verdict.application;

import com.trendmicro.tlsh.Tlsh;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
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
public class SimilarityDecisions {
    public static final String POLICY = "tlsh-30-70-v1";
    private final JdbcTemplate jdbc;

    public SimilarityDecisions(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Assessment(String sample, String tlsh, Integer distance, String classification, String token) {}

    /** 직접 SubmitFile을 호출해도 정확 SHA 예외가 우선한다. TLSH로 허용하는 경로는 만들지 않는다. */
    public SubmitFileResult exactOverride(String sha) {
        boolean whitelisted = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM hash_whitelist WHERE hash_value=? AND is_active
                    AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP))
                """, Boolean.class, sha));
        if (whitelisted) {
            return new SubmitFileResult("ALLOW", "WHITELIST", null, List.of());
        }
        List<String> reasons = jdbc.queryForList("""
                SELECT reason FROM hash_blacklist
                WHERE hash_type='SHA256' AND hash_value=? AND is_active
                """, String.class, sha);
        return reasons.isEmpty() ? null : new SubmitFileResult("BLOCK", "BLACKLIST", reasons.getFirst(), List.of());
    }

    /** V8의 목록/지문 쓰기 트리거는 같은 키의 배타 잠금을 얻는다. 비교에서 커밋까지 후보가 고정된다. */
    public void lockCorpus() {
        jdbc.execute("SELECT pg_advisory_xact_lock_shared(74190321)");
    }

    /** corpus -> 파일 순서로 획득한다. 새 SHA 동시 업로드도 행이 생기기 전부터 직렬화한다. */
    public void lockFile(String sha) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(74190322, hashtext(?))", sha);
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
        String token = token();
        List<String> values = jdbc.queryForList("""
                SELECT tlsh FROM file_fingerprints WHERE sha256=? AND calculation_status='CALCULATED'
                """, String.class, sha);
        if (values.isEmpty()) {
            return new Assessment(null, null, null, "UNAVAILABLE", token);
        }
        Tlsh input = Tlsh.fromTlshStr(values.getFirst());
        Assessment[] best = {new Assessment(null, null, null, "NONE", token)};
        // PostgreSQL 커서로 전 후보를 읽는다. fetchSize는 전송 묶음이지 후보 수 상한이 아니다.
        // 거리 동률이면 SHA가 작은 샘플을 선택하여 실행 순서나 DB 계획이 달라도 근거가 같다.
        jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "SELECT sha256, tlsh FROM active_malicious_fingerprints");
            statement.setFetchSize(256);
            return statement;
        }, (RowCallbackHandler) rs -> {
            String hash = rs.getString(1);
            String tlsh = rs.getString(2);
            int distance = input.totalDiff(Tlsh.fromTlshStr(tlsh), true);
            Assessment current = best[0];
            if (current.distance() == null || distance < current.distance()
                    || (distance == current.distance() && hash.compareTo(current.sample()) < 0)) {
                String classification;
                if (distance <= 30) {
                    classification = "BLOCK";
                } else if (distance <= 70) {
                    // 경고는 허용 판정을 바꾸지 않는다. SUSPICIOUS는 기존 파이프라인에서 차단이므로 쓰지 않는다.
                    classification = "WARN";
                } else {
                    classification = "NONE";
                }
                best[0] = new Assessment(hash, tlsh, distance, classification, token);
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
                """, id, sha, analysis, assessment.token(), POLICY, assessment.sample(), assessment.tlsh(),
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
}
