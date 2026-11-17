-- sha256 컬럼을 CHAR(64)에서 VARCHAR(64)로 바꾼다. 값·길이·NOT NULL·PK·FK·CHECK·인덱스는 그대로다.
-- CHAR(64) 열을 hash_blacklist/hash_whitelist.hash_value(VARCHAR(128)) 같은 varchar·text 값과 비교하면
-- 한쪽이 bpchar나 text로 바뀌어 그쪽 인덱스를 못 쓴다. MalwareBazaar 적재(117만 행) 뒤 트리거(V15)와
-- 대시보드 뷰(V17)가 순차 스캔으로 수 초~수십 초 걸렸고, 비교마다 캐스트를 붙여 막아 왔다. 타입을 맞춰 캐스트를 없앤다.
-- 운영 주의: ALTER COLUMN TYPE은 표를 다시 쓰며 잠근다. file_fingerprints(117만 행)가 잠긴 동안 CheckHash가 멈추고
-- fail-close로 다운로드가 차단된다(사용자 없음, 2026-11-17 결정).

-- 1) 열 타입에 묶인 뷰는 타입을 바꾸기 전에 지운다. 아래 3)에서 같은 정의로 다시 만든다.
DROP VIEW download_event_threats;
DROP VIEW current_similarity_corpus_token;
DROP VIEW active_malicious_fingerprints;

-- 2) 타입 변경. file_verdicts.sha256을 참조하는 FK 4개(analyses, quarantine_files, pending_quarantine_uploads,
--    similarity_cache)가 함께 다시 검증된다.
ALTER TABLE file_verdicts ALTER COLUMN sha256 TYPE VARCHAR(64);
ALTER TABLE download_events ALTER COLUMN sha256 TYPE VARCHAR(64);
ALTER TABLE analyses ALTER COLUMN sha256 TYPE VARCHAR(64);
ALTER TABLE quarantine_files ALTER COLUMN sha256 TYPE VARCHAR(64);
ALTER TABLE pending_quarantine_uploads ALTER COLUMN sha256 TYPE VARCHAR(64);
ALTER TABLE similarity_assessments
    ALTER COLUMN sha256 TYPE VARCHAR(64),
    ALTER COLUMN matched_sample_sha256 TYPE VARCHAR(64);
ALTER TABLE similarity_cache ALTER COLUMN sha256 TYPE VARCHAR(64);
ALTER TABLE event_decision_evidence ALTER COLUMN sha256 TYPE VARCHAR(64);
ALTER TABLE file_fingerprints ALTER COLUMN sha256 TYPE VARCHAR(64);

-- 3) 뷰 재생성. active_malicious_fingerprints는 V8, current_similarity_corpus_token은 V10 정의 그대로다.
CREATE VIEW active_malicious_fingerprints AS
SELECT f.sha256, f.tlsh, f.algorithm_version, f.provenance
FROM file_fingerprints f
JOIN hash_blacklist b ON b.hash_type = 'SHA256' AND b.hash_value = f.sha256 AND b.is_active
WHERE f.calculation_status = 'CALCULATED'
  AND NOT EXISTS (
      SELECT 1 FROM hash_whitelist w
      WHERE w.hash_type = 'SHA256' AND w.hash_value = f.sha256 AND w.is_active
        AND (w.expires_at IS NULL OR w.expires_at > CURRENT_TIMESTAMP)
  );

CREATE VIEW current_similarity_corpus_token AS
SELECT revision::text || ':' || generation::text || ':' || (SELECT count(*) FROM hash_whitelist
    WHERE is_active AND expires_at <= CURRENT_TIMESTAMP
      AND EXISTS(SELECT 1 FROM file_fingerprints f JOIN hash_blacklist b ON b.hash_value=f.sha256
          WHERE f.sha256=hash_whitelist.hash_value AND f.calculation_status='CALCULATED'
            AND b.hash_type='SHA256' AND b.is_active))::text || ':tlsh-30-70-v1' AS token
FROM similarity_corpus_state;

-- download_event_threats는 V17 정의에서 블랙리스트 조인의 ::text 캐스트만 뺐다(이제 varchar끼리 비교).
CREATE VIEW download_event_threats AS
WITH latest_success_analysis AS (
    -- 같은 sha256의 가장 최근 SUCCESS 분석 1건. TIMEOUT/CRASH/OOM은 애초에 후보에서 빠진다.
    SELECT DISTINCT ON (sha256) analysis_id, sha256
    FROM analyses
    WHERE status = 'SUCCESS'
    ORDER BY sha256, analyzed_at DESC
),
ranked_matches AS (
    SELECT
        lsa.sha256,
        am.rule_name,
        am.severity,
        CASE am.severity
            WHEN 'CRITICAL' THEN 4 WHEN 'HIGH' THEN 3 WHEN 'MEDIUM' THEN 2 WHEN 'LOW' THEN 1 ELSE 0
        END AS severity_rank,
        COUNT(*) OVER (PARTITION BY lsa.sha256) AS match_count,
        ROW_NUMBER() OVER (
            PARTITION BY lsa.sha256
            ORDER BY
                (CASE am.severity
                    WHEN 'CRITICAL' THEN 4 WHEN 'HIGH' THEN 3 WHEN 'MEDIUM' THEN 2 WHEN 'LOW' THEN 1 ELSE 0
                END) DESC,
                am.rule_name ASC
        ) AS rule_rank
    FROM latest_success_analysis lsa
    JOIN analysis_matches am ON am.analysis_id = lsa.analysis_id
),
top_match AS (
    SELECT sha256, rule_name AS top_rule_name, severity AS top_severity,
           severity_rank AS top_severity_rank, match_count
    FROM ranked_matches
    WHERE rule_rank = 1
)
SELECT
    de.event_id,
    CASE
        WHEN de.decision = 'FAIL_CLOSE' THEN NULL
        WHEN de.decision = 'BLOCKED' AND de.decision_source = 'SIMILARITY' THEN 'MEDIUM'
        WHEN de.decision_source = 'BLACKLIST' THEN bl.severity
        WHEN de.decision_source IN ('ENGINE', 'CACHE') THEN tm.top_severity
        ELSE NULL
    END AS severity,
    CASE
        WHEN de.decision = 'FAIL_CLOSE' THEN NULL
        WHEN de.decision = 'BLOCKED' AND de.decision_source = 'SIMILARITY' THEN 2
        WHEN de.decision_source = 'BLACKLIST' THEN
            CASE bl.severity
                WHEN 'CRITICAL' THEN 4 WHEN 'HIGH' THEN 3 WHEN 'MEDIUM' THEN 2 WHEN 'LOW' THEN 1 ELSE NULL
            END
        WHEN de.decision_source IN ('ENGINE', 'CACHE') THEN tm.top_severity_rank
        ELSE NULL
    END AS severity_rank,
    CASE WHEN de.decision_source IN ('ENGINE', 'CACHE') THEN tm.top_rule_name ELSE NULL END AS top_rule_name,
    CASE
        WHEN de.decision_source IN ('ENGINE', 'CACHE') AND tm.match_count IS NOT NULL
            THEN tm.match_count - 1
        ELSE NULL
    END AS extra_rule_count,
    CASE
        WHEN de.decision = 'FAIL_CLOSE' THEN 'FAIL_CLOSE'
        WHEN de.decision_source IN ('ENGINE', 'CACHE', 'BLACKLIST', 'SIMILARITY') THEN de.decision_source
        ELSE NULL
    END AS reason_type,
    (ack.event_id IS NOT NULL) AS acknowledged
FROM download_events de
LEFT JOIN top_match tm ON tm.sha256 = de.sha256
LEFT JOIN hash_blacklist bl ON bl.hash_type = 'SHA256' AND bl.hash_value = de.sha256
LEFT JOIN download_event_acknowledgements ack ON ack.event_id = de.event_id;

-- 4) V15가 CHAR 열의 인덱스를 쓰려고 넣은 ::bpchar 캐스트를 뺀다. 남겨 두면 이제 varchar 열 쪽이 bpchar로 바뀌어
--    인덱스를 못 쓴다. key_sha(TEXT)와 varchar 열 비교는 캐스트 없이 인덱스를 쓴다. 나머지 본문은 V15와 같다.
CREATE OR REPLACE FUNCTION revise_similarity_corpus() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE changed BOOLEAN := true; key_sha TEXT;
BEGIN
    IF TG_TABLE_NAME = 'file_fingerprints' THEN
        key_sha := CASE WHEN TG_OP = 'DELETE' THEN OLD.sha256 ELSE NEW.sha256 END;
        IF (TG_OP = 'INSERT' AND NEW.calculation_status = 'CALCULATED')
           OR (TG_OP = 'DELETE' AND OLD.calculation_status = 'CALCULATED')
           OR (TG_OP = 'UPDATE'
               AND (OLD.calculation_status = 'CALCULATED' OR NEW.calculation_status = 'CALCULATED')
               AND (OLD.tlsh, OLD.calculation_status, OLD.algorithm_version)
                   IS DISTINCT FROM (NEW.tlsh, NEW.calculation_status, NEW.algorithm_version)) THEN
            -- 산출 불가끼리의 보강은 비교 결과를 바꾸지 않는다. 새 유효 지문은 반드시 재평가한다.
            DELETE FROM similarity_cache WHERE sha256=key_sha;
        END IF;
        changed := EXISTS(SELECT 1 FROM hash_blacklist WHERE hash_type='SHA256' AND hash_value=key_sha AND is_active);
        IF TG_OP = 'UPDATE' THEN
            changed := changed AND (OLD.tlsh, OLD.calculation_status, OLD.algorithm_version)
                IS DISTINCT FROM (NEW.tlsh, NEW.calculation_status, NEW.algorithm_version);
        END IF;
    ELSE
        key_sha := CASE WHEN TG_OP = 'DELETE' THEN OLD.hash_value ELSE NEW.hash_value END;
        -- 지문 없는 SHA와 설명/심각도 변경은 후보 집합을 바꾸지 않는다.
        changed := EXISTS(SELECT 1 FROM file_fingerprints WHERE sha256=key_sha AND calculation_status='CALCULATED');
        IF TG_TABLE_NAME = 'hash_blacklist' THEN
            changed := changed AND CASE WHEN TG_OP='INSERT' THEN NEW.hash_type='SHA256' AND NEW.is_active
                         WHEN TG_OP='DELETE' THEN OLD.hash_type='SHA256' AND OLD.is_active
                         ELSE (OLD.hash_type, OLD.hash_value, OLD.is_active) IS DISTINCT FROM
                              (NEW.hash_type, NEW.hash_value, NEW.is_active) END;
        ELSE
            changed := changed AND EXISTS(SELECT 1 FROM hash_blacklist WHERE hash_type='SHA256' AND hash_value=key_sha AND is_active)
                AND CASE WHEN TG_OP='INSERT' THEN NEW.is_active
                         WHEN TG_OP='DELETE' THEN OLD.is_active
                         ELSE (OLD.hash_value, OLD.is_active, OLD.expires_at) IS DISTINCT FROM
                              (NEW.hash_value, NEW.is_active, NEW.expires_at) END;
        END IF;
    END IF;
    IF changed THEN UPDATE similarity_corpus_state SET revision=revision+1, generation=gen_random_uuid(); END IF;
    RETURN NULL;
END $$;
