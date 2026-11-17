-- download_event_threats 뷰의 블랙리스트 조인이 PK 인덱스를 쓰게 한다. 동작(무엇을 보여 주는지)은 V12와 같다.
-- download_events.sha256은 char(64), hash_blacklist.hash_value는 varchar(128)라 그냥 비교하면 hash_value 쪽이
-- bpchar로 바뀌어 pk_hash_blacklist(hash_type, hash_value)를 못 쓴다. MalwareBazaar 적재(117만 행) 뒤 운영에서
-- 이 뷰를 조인하는 대시보드 쿼리가 20초 걸려 콘솔 요청이 끊겼다(2026-11-17). 작은 쪽(de.sha256)을 text로 바꾼다.
-- sha256은 늘 64자 16진수라 char 패딩 공백이 없어 비교 결과는 같다. V15와 같은 종류의 수정이다.
CREATE OR REPLACE VIEW download_event_threats AS
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
LEFT JOIN hash_blacklist bl ON bl.hash_type = 'SHA256' AND bl.hash_value = de.sha256::text
LEFT JOIN download_event_acknowledgements ack ON ack.event_id = de.event_id;
