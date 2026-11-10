-- 확인(acknowledge) 상태. "누가·언제 확인했는지"는 행이 있고 없고로 표현한다(quarantine_files.restored_by 패턴과 동일).
CREATE TABLE download_event_acknowledgements (
    event_id        UUID        NOT NULL,
    acknowledged_by UUID        NOT NULL,
    acknowledged_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_download_event_acknowledgements PRIMARY KEY (event_id),
    CONSTRAINT fk_download_event_acknowledgements_event FOREIGN KEY (event_id)
        REFERENCES download_events (event_id),
    CONSTRAINT fk_download_event_acknowledgements_admin FOREIGN KEY (acknowledged_by)
        REFERENCES admin_users (admin_id)
);

CREATE INDEX idx_events_decision_created_at ON download_events (decision, created_at);

-- 위협 대시보드 전용 집계 뷰. download_events 한 행 = 이 뷰 한 행.
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
        WHEN de.decision_source = 'BLACKLIST' THEN bl.severity
        WHEN de.decision_source IN ('ENGINE', 'CACHE') THEN tm.top_severity
        ELSE NULL
    END AS severity,
    CASE
        WHEN de.decision = 'FAIL_CLOSE' THEN NULL
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
        WHEN de.decision_source IN ('ENGINE', 'CACHE', 'BLACKLIST') THEN de.decision_source
        ELSE NULL
    END AS reason_type,
    (ack.event_id IS NOT NULL) AS acknowledged
FROM download_events de
LEFT JOIN top_match tm ON tm.sha256 = de.sha256
LEFT JOIN hash_blacklist bl ON bl.hash_type = 'SHA256' AND bl.hash_value = de.sha256
LEFT JOIN download_event_acknowledgements ack ON ack.event_id = de.event_id;
