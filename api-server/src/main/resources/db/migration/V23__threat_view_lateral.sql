-- 대시보드 위협 뷰를 이벤트 단위 LATERAL로 바꾸고 대시보드 읽기용 인덱스를 더한다.
-- 뷰 이름·열 이름·열 타입·결과는 V18(= V17에서 ::text 캐스트만 뺀 정의)과 같다.
--
-- 왜: V17/V18 뷰는 top_match CTE(analyses 전체에 DISTINCT ON + analysis_matches 전체에 윈도 함수)를 먼저 만들고
-- download_events 전체와 LEFT JOIN 했다. 바깥에서 기간·차단 조건을 걸어도 뷰 안에서는 download_events 전체를
-- 순차 스캔했다(PC 1천 대·이벤트 361만 건에서 이벤트 목록 30일 p95 27초, 2026-11-27 측정).
-- 이제 뷰는 download_events에서 시작하고, 대표 룰은 그 행의 sha256으로 LATERAL에서 찾는다. 바깥 조건으로
-- 걸러진 행에만 위협 정보를 붙인다.
--
-- 같은 결과인 이유:
--  * 최신 SUCCESS 분석 1건: DISTINCT ON (sha256) ORDER BY analyzed_at DESC = 같은 sha256에서 ORDER BY analyzed_at DESC LIMIT 1
--    (analyzed_at 동점이면 둘 다 아무 행이나 고른다. 이전 정의도 동점 순서는 정해져 있지 않았다)
--  * 대표 룰: ROW_NUMBER() ... rule_rank = 1 = ORDER BY 심각도 DESC, rule_name ASC LIMIT 1 (uq_analysis_matches로 (analysis_id, rule_name) 유일)
--  * match_count: COUNT(*) OVER (PARTITION BY sha256) = 최신 분석의 매치 수 = LIMIT 전에 계산한 COUNT(*) OVER ()
--  * 매치가 없거나 SUCCESS 분석이 없으면 이전처럼 tm 열이 전부 NULL
--  * tm 값은 decision_source가 ENGINE·CACHE일 때만 쓰이므로(아래 CASE) 그 행에서만 LATERAL을 계산한다
CREATE OR REPLACE VIEW download_event_threats AS
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
LEFT JOIN LATERAL (
    SELECT
        am.rule_name AS top_rule_name,
        am.severity AS top_severity,
        CASE am.severity
            WHEN 'CRITICAL' THEN 4 WHEN 'HIGH' THEN 3 WHEN 'MEDIUM' THEN 2 WHEN 'LOW' THEN 1 ELSE 0
        END AS top_severity_rank,
        COUNT(*) OVER () AS match_count
    FROM (
        -- 같은 sha256의 가장 최근 SUCCESS 분석 1건(idx_analyses_sha256). TIMEOUT/CRASH/OOM은 후보에서 빠진다.
        SELECT an.analysis_id
        FROM analyses an
        WHERE an.sha256 = de.sha256 AND an.status = 'SUCCESS'
        ORDER BY an.analyzed_at DESC
        LIMIT 1
    ) lsa
    JOIN analysis_matches am ON am.analysis_id = lsa.analysis_id
    WHERE de.decision_source IN ('ENGINE', 'CACHE')
    ORDER BY 3 DESC, am.rule_name ASC
    LIMIT 1
) tm ON true
LEFT JOIN hash_blacklist bl ON bl.hash_type = 'SHA256' AND bl.hash_value = de.sha256
LEFT JOIN download_event_acknowledgements ack ON ack.event_id = de.event_id;

-- 차단·FAIL_CLOSE 부분 인덱스. 기존 idx_events_blocked는 BLOCKED만 담아 decision IN ('BLOCKED','FAIL_CLOSE')에 못 쓴다.
-- agent_id를 함께 담아 summary의 차단 PC 수(COUNT DISTINCT)를 힙 없이 센다.
CREATE INDEX idx_events_block_created_at ON download_events (created_at) INCLUDE (agent_id)
    WHERE decision IN ('BLOCKED', 'FAIL_CLOSE');

-- summary 건수용 커버링 인덱스. 기간 안 행을 인덱스 전용 스캔으로 센다.
CREATE INDEX idx_events_created_at_covering ON download_events (created_at)
    INCLUDE (agent_id, decision, decision_source);

-- 최신 SUCCESS 분석 1건 조회는 기존 idx_analyses_sha256 (sha256, analyzed_at DESC)를 쓴다(새로 만들지 않는다).
-- 분석은 sha256마다 몇 건뿐이라 status는 힙에서 걸러도 충분하다.
