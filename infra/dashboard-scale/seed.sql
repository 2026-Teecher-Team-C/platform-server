-- 대기업 규모 대시보드 측정용 목데이터 생성기. 전체가 한 트랜잭션이라 중간에 실패하면 아무것도 바뀌지 않는다.
--
--   psql -v ON_ERROR_STOP=1 -v confirm_wipe=yes -v expect_db=<DB 이름> \
--        -v pcs=1000 -v days=180 -v per_day=20 -v seed=0.42 -v now="'2026-11-27T00:00:00Z'" -f seed.sql
--
--   pcs      PC(에이전트) 수.  days  보관 일수.  per_day  PC당 하루 평균 이벤트 수.
--   seed     -1~1. random()을 고정한다(같은 인자면 같은 데이터).
--   now      생성 기준 시각(따옴표 포함). 측정 시작 시각을 넣는다. 이벤트는 [now - days일, now] 안에만 생긴다.
--   confirm_wipe / expect_db  안전장치. confirm_wipe=yes 가 아니거나 current_database() 가 expect_db 와 다르면
--            아무것도 지우지 않고 실패한다.
--
-- !! 측정 전용 DB에서만 실행한다(운영·공유 DB 금지). 아래 표는 FK를 따라 TRUNCATE ... CASCADE 로 통째로 비워진다.
--   명시:   download_event_acknowledgements, analysis_matches, analyses, download_events,
--           similarity_cache, similarity_assessments
--   CASCADE 로 딸려오는 것(FK 확인): quarantine_files, pending_quarantine_uploads, event_decision_evidence,
--           hash_whitelist
--   골라서 지움: file_verdicts(sha256이 '5ca1e'로 시작), agents/devices(hostname이 'scale-'로 시작)
--   건드리지 않음: hash_blacklist, file_fingerprints, admin_users(scale-seed-admin만 upsert) 등 그 밖의 모든 표
-- 메모리: download_events INSERT는 FK 2개(agents, similarity_assessments 등)마다 행당 AFTER 트리거 이벤트를 큐에 쌓고
--   문장이 끝나야 비운다(행당 약 12바이트 x FK 수). 10k PC(약 3,600만 행)를 한 문장으로 넣으면 큐만 약 860MB라
--   postgres(768m)가 OOM으로 죽고 전체가 롤백된다. 그래서 이벤트는 날짜(KST 하루)마다 INSERT 한 문장씩 넣는다
--   (PL/pgSQL DO 루프, 오래된 날부터). 문장이 끝날 때마다 큐가 비워져 최대 큐는 하루치(10k PC에서 약 28만 행)다.
--   FK·트리거는 끄지 않는다(바인딩 트리거 bind_event_similarity만 아래 이유로 끈다). setseed는 루프 앞에서 한 번만 한다.
-- 시각: 각 이벤트는 [now - days일, now) 연속 구간에서 (날짜 가중치 x 시간대 가중치)로 뽑는다. 날짜 안에서 정한 시각이
--   구간 밖(now 이후, 시작 이전)이면 버린다. 그래서 마지막 24시간은 평일 하루치에 시간대 가중치를 곱한 만큼만 갖는다.
-- 판정 비율은 운영 비율 조회 전의 기본값이다(RELEASED 98.6 / BLOCKED 1.0 / FAIL_CLOSE 0.2 / BYPASSED 0.2).

\set ON_ERROR_STOP on
BEGIN;
-- 안전장치: 지우기 전에 확인한다(변수가 없으면 빈 문자열로 보아 실패한다).
\if :{?confirm_wipe}
\else
\set confirm_wipe ''
\endif
\if :{?expect_db}
\else
\set expect_db ''
\endif
CREATE FUNCTION pg_temp.scale_abort(msg text) RETURNS void LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION '%', msg; END $$;
SELECT CASE WHEN :'confirm_wipe' = 'yes' AND :'expect_db' <> '' AND current_database() = :'expect_db'
            THEN NULL
            ELSE pg_temp.scale_abort('중단: -v confirm_wipe=yes 와 -v expect_db=<현재 DB 이름>이 모두 맞아야 한다 (현재 DB: '
                                     || current_database() || ')') END;
SELECT setseed(:seed);
SET LOCAL synchronize_seqscans = off;
SET LOCAL work_mem = '256MB';

-- 0. 비우기 ---------------------------------------------------------------------------------------------
TRUNCATE download_event_acknowledgements, analysis_matches, analyses, download_events,
         similarity_cache, similarity_assessments CASCADE;  -- 딸려 비워지는 표는 머리말 참고
DELETE FROM file_verdicts WHERE sha256 LIKE '5ca1e%';
DELETE FROM agents WHERE device_id IN (SELECT device_id FROM devices WHERE hostname LIKE 'scale-%');
DELETE FROM devices WHERE hostname LIKE 'scale-%';

-- 풀 크기. 이름 있는 상수처럼 temp 테이블 한 행에 둔다.
CREATE TEMP TABLE scale_params ON COMMIT DROP AS
SELECT :pcs::int AS pcs, :days::int AS days, :per_day::numeric AS per_day, :now::timestamptz AS now,
       :pcs::int * 50 AS clean_pool,                 -- 정상 파일 sha 풀: PC 수 x 50
       GREATEST(:pcs::int * 5, 20) AS mal_pool,      -- ENGINE/CACHE로 차단되는 악성 sha 풀
       GREATEST(:pcs::int, 20) AS sim_pool,          -- SIMILARITY로 차단되는 변종 sha 풀
       GREATEST(:pcs::int / 2, 10) AS host_pool;     -- request_host 도메인 수: PC 수 / 2

-- sha 이름 규칙. 접두어 '5ca1e'로 이 스크립트 소유임을 표시한다(64자 16진수 유지).
CREATE FUNCTION pg_temp.scale_sha(kind text, i bigint) RETURNS varchar LANGUAGE sql IMMUTABLE AS
$$ SELECT left('5ca1e' || md5(kind || ':a:' || i) || md5(kind || ':b:' || i), 64)::varchar $$;
-- 거듭제곱 분포로 1..n 고르기(u는 0~1 난수. 작은 번호에 몰리고 k가 클수록 더 몰린다).
CREATE FUNCTION pg_temp.scale_idx(n bigint, k numeric, u double precision) RETURNS bigint LANGUAGE sql IMMUTABLE AS
$$ SELECT 1 + floor(n * power(u, k))::bigint $$;

-- 1. devices / agents -----------------------------------------------------------------------------------
INSERT INTO devices (device_id, hostname, os_platform, created_at, updated_at)
SELECT md5('scale-device-' || i)::uuid, 'scale-pc-' || lpad(i::text, 6, '0'),
       (ARRAY['WINDOWS', 'WINDOWS', 'WINDOWS', 'MACOS', 'LINUX'])[1 + i % 5],
       p.now - make_interval(days => p.days), p.now - make_interval(days => p.days)
FROM scale_params p, generate_series(1, (SELECT pcs FROM scale_params)) i;

INSERT INTO agents (agent_id, device_id, agent_version, enrollment_token_hash, status, last_heartbeat_at,
                    created_at, updated_at)
SELECT md5('scale-agent-' || i)::uuid, md5('scale-device-' || i)::uuid, '0.3.0', 'scale-' || i, CASE WHEN i % 33 = 1 THEN 'REVOKED' ELSE 'ACTIVE' END,
       p.now - make_interval(secs => floor(random() * 600)),
       p.now - make_interval(days => p.days), p.now
FROM scale_params p, generate_series(1, (SELECT pcs FROM scale_params)) i;

-- 2. YARA 룰 이름(심각도 고정) ---------------------------------------------------------------------------
CREATE TEMP TABLE scale_rules ON COMMIT DROP AS
SELECT rn, 'Scale_' || (ARRAY['Trojan', 'Ransom', 'Stealer', 'Loader', 'Miner', 'Backdoor'])[1 + rn % 6]
           || '_' || lpad(rn::text, 2, '0') AS rule_name,
       (ARRAY['LOW', 'MEDIUM', 'MEDIUM', 'HIGH', 'HIGH', 'CRITICAL'])[1 + rn % 6] AS severity
FROM generate_series(0, 29) rn;

-- 3. file_verdicts(sha 풀) + analyses + analysis_matches --------------------------------------------------
-- 정상 풀: 파일마다 SUCCESS 분석 1건(소수는 TIMEOUT/CRASH, 엔진 상태 화면용). 매칭은 없다.
INSERT INTO file_verdicts (sha256, file_size, detected_file_type, verdict, verdict_source, rulesets_version,
                           analysis_count, first_seen_at, last_verdict_at)
SELECT pg_temp.scale_sha('clean', i), 10000 + (i * 7919) % 5000000, (ARRAY['PE', 'PE', 'ZIP', 'PDF', 'ELF'])[1 + i % 5],
       'CLEAN', 'ENGINE', 0, 1, p.now - make_interval(days => p.days), p.now
FROM scale_params p, generate_series(1, (SELECT clean_pool FROM scale_params)) i;

-- 악성 풀(ENGINE/CACHE 차단용)
INSERT INTO file_verdicts (sha256, file_size, detected_file_type, verdict, verdict_source, rulesets_version,
                           analysis_count, first_seen_at, last_verdict_at)
SELECT pg_temp.scale_sha('mal', i), 10000 + (i * 7919) % 5000000, 'PE', 'MALICIOUS', 'ENGINE', 0, 1,
       p.now - make_interval(days => p.days), p.now
FROM scale_params p, generate_series(1, (SELECT mal_pool FROM scale_params)) i;

INSERT INTO analyses (analysis_id, sha256, rulesets_version, engine_version, status, verdict, detected_file_type,
                      duration_ms, error_message, analyzed_at)
SELECT md5('scale-analysis-clean-' || g.i)::uuid, pg_temp.scale_sha('clean', g.i), 0, '1.0.0', s.status,
       CASE s.status WHEN 'SUCCESS' THEN 'CLEAN' ELSE 'ERROR' END, 'PE', 20 + floor(random() * 400)::int,
       CASE s.status WHEN 'SUCCESS' THEN NULL ELSE 'scale seed' END,
       p.now - make_interval(secs => floor(random() * p.days * 86400))
FROM scale_params p, (SELECT i, random() AS u FROM generate_series(1, (SELECT clean_pool FROM scale_params)) i) g
CROSS JOIN LATERAL (SELECT CASE WHEN g.u < 0.98 THEN 'SUCCESS' WHEN g.u < 0.99 THEN 'TIMEOUT' ELSE 'CRASH' END AS status) s;

INSERT INTO analyses (analysis_id, sha256, rulesets_version, engine_version, status, verdict, detected_file_type,
                      duration_ms, analyzed_at)
SELECT md5('scale-analysis-mal-' || i)::uuid, pg_temp.scale_sha('mal', i), 0, '1.0.0', 'SUCCESS', 'MALICIOUS', 'PE',
       50 + floor(random() * 900)::int, p.now - make_interval(secs => floor(random() * p.days * 86400))
FROM scale_params p, generate_series(1, (SELECT mal_pool FROM scale_params)) i;

-- 악성 분석마다 매칭 1~3개(rule_name은 분석 안에서 겹치지 않게 3씩 건너뛴다)
INSERT INTO analysis_matches (match_id, analysis_id, rule_name, severity)
SELECT md5('scale-match-' || m.i || '-' || j)::uuid, md5('scale-analysis-mal-' || m.i)::uuid, r.rule_name, r.severity
FROM (SELECT i, 1 + floor(random() * 3)::int AS k FROM generate_series(1, (SELECT mal_pool FROM scale_params)) i) m
CROSS JOIN LATERAL generate_series(0, m.k - 1) j
JOIN scale_rules r ON r.rn = (m.i * 7 + j * 3) % 30;

-- 4. 블랙리스트 풀 / SIMILARITY 근거 -------------------------------------------------------------------------
-- BLACKLIST 차단과 SIMILARITY 기준 샘플은 hash_blacklist의 활성 SHA256에서 고른다.
CREATE TEMP TABLE scale_bl ON COMMIT DROP AS
SELECT row_number() OVER () AS n, hash_value
FROM (SELECT hash_value FROM hash_blacklist WHERE hash_type = 'SHA256' AND is_active ORDER BY random() LIMIT 2000) s;
DO $$ BEGIN
    IF (SELECT count(*) FROM scale_bl) = 0 THEN
        RAISE EXCEPTION 'hash_blacklist에 활성 SHA256이 없다. loadtest-seed 등으로 먼저 채운다';
    END IF;
END $$;

-- SIMILARITY 차단 변종 sha 하나당 BLOCK 평가 1건. file_verdicts 행은 없다(업로드 전 TLSH 차단과 같다).
INSERT INTO similarity_assessments (assessment_id, sha256, corpus_token, policy_version, matched_sample_sha256,
                                    matched_sample_tlsh, distance, classification, input_source, assessed_at)
SELECT md5('scale-assessment-' || i)::uuid, pg_temp.scale_sha('sim', i), 'scale-seed', 'tlsh-30-70-v1',
       (SELECT hash_value FROM scale_bl WHERE n = 1 + i % (SELECT count(*) FROM scale_bl)),
       'T1' || left(upper(md5('tlsh-a-' || i) || md5('tlsh-b-' || i) || md5('tlsh-c-' || i)), 70),
       1 + floor(random() * 30)::int, 'BLOCK', 'AGENT_CLAIMED', p.now
FROM scale_params p, generate_series(1, (SELECT sim_pool FROM scale_params)) i;

-- 5. download_events ---------------------------------------------------------------------------------------
-- 바인딩 트리거(V11)는 근거를 이벤트 INSERT 뒤에 못 찾는다. 행마다 조회하면 느리므로 끄고
-- similarity_assessment_id 를 직접 채운다. 트랜잭션 안이라 실패하면 트리거 상태도 되돌아간다.
ALTER TABLE download_events DISABLE TRIGGER bind_event_similarity;

-- 날짜별 가중치: 평일 1.0, 주말 0.2를 기간 평균 1이 되게 정규화한다. 기준일은 now의 KST 날짜(0일 전 = 오늘).
-- 구간 양 끝의 부분 날짜까지 덮도록 days+1일치를 둔다(구간 밖 시각은 아래에서 버린다).
CREATE TEMP TABLE scale_days ON COMMIT DROP AS
WITH d AS (
    SELECT g AS ago,
           (date_trunc('day', p.now AT TIME ZONE 'Asia/Seoul') - make_interval(days => g)) AS kst_day
    FROM scale_params p, generate_series(0, (SELECT days FROM scale_params)) g
), w AS (
    SELECT ago, kst_day, CASE WHEN extract(isodow FROM kst_day) <= 5 THEN 1.0 ELSE 0.2 END AS base FROM d
)
SELECT ago, kst_day, base / avg(base) OVER () AS weight FROM w;

DO $loop$
DECLARE d_ago int;
BEGIN
FOR d_ago IN SELECT ago FROM scale_days ORDER BY ago DESC LOOP  -- 하루에 INSERT 한 문장
INSERT INTO download_events (event_id, agent_id, sha256, request_host, url, filename, mime_type, file_size,
                             pipeline_status, decision, decision_source, cache_hit, bytes_uploaded,
                             held_at, decided_at, hold_duration_ms, created_at, similarity_assessment_id)
SELECT gen_random_uuid(), x.agent_id, x.sha256, x.host,
       'https://' || x.host || '/files/' || left(coalesce(x.sha256, 'unknown'), 12) || '.bin',
       left(coalesce(x.sha256, 'unknown'), 12) || '.bin', 'application/octet-stream', x.size,
       'COMPLETED', x.decision, x.source, x.source = 'CACHE',
       CASE WHEN x.source = 'ENGINE' THEN x.size ELSE 0 END,
       x.ts - make_interval(secs => x.hold_ms / 1000.0), x.ts, x.hold_ms, x.ts, x.assessment_id
FROM (
    SELECT t.agent_id, t.ts, t.host, t.size, t.hold_ms, t.decision, t.source,
           CASE
               WHEN t.decision IN ('FAIL_CLOSE', 'BYPASSED') THEN NULL
               WHEN t.decision = 'RELEASED' THEN pg_temp.scale_sha('clean', pg_temp.scale_idx(p.clean_pool, 4, t.u_sha))
               WHEN t.source = 'BLACKLIST' THEN (SELECT hash_value FROM scale_bl
                                                 WHERE n = pg_temp.scale_idx((SELECT count(*) FROM scale_bl), 2, t.u_sha))
               WHEN t.source = 'SIMILARITY' THEN pg_temp.scale_sha('sim', pg_temp.scale_idx(p.sim_pool, 3, t.u_sha))
               ELSE pg_temp.scale_sha('mal', pg_temp.scale_idx(p.mal_pool, 2, t.u_sha))
           END AS sha256,
           CASE WHEN t.source = 'SIMILARITY'
                THEN md5('scale-assessment-' || pg_temp.scale_idx(p.sim_pool, 3, t.u_sha))::uuid END AS assessment_id
    FROM (
        -- 난수는 전부 이 SELECT 목록에서 뽑는다. 행마다 새로 계산되려면 목록 안에 있어야 한다
        -- (바깥 값에 안 묶인 LATERAL은 플래너가 한 번만 계산해 모든 행이 같은 값을 갖는다).
        SELECT b.agent_id, b.decision, b.source, b.u_sha,
               (b.kst_day + make_interval(secs => CASE WHEN b.u_in < 0.8 THEN (9 + b.u_hour * 9) * 3600
                                                       ELSE b.u_hour * 86400 END)) AT TIME ZONE 'Asia/Seoul' AS ts,
               'dl-' || lpad(pg_temp.scale_idx(b.host_pool, 3, b.u_host)::text, 5, '0') || '.example.com' AS host,
               (10000 + floor(b.u_tail * 5000000))::bigint AS size,
               (30 + floor(b.u_hold * 3000))::int AS hold_ms
        FROM (
            SELECT b0.*,
                   CASE WHEN b0.u < 0.010 THEN 'BLOCKED' WHEN b0.u < 0.012 THEN 'FAIL_CLOSE'
                        WHEN b0.u < 0.014 THEN 'BYPASSED' ELSE 'RELEASED' END AS decision,
                   CASE
                       WHEN b0.u < 0.010 THEN                        -- BLOCKED: BLACKLIST 40 / SIMILARITY 20 / ENGINE 25 / CACHE 15
                           CASE WHEN b0.v < 0.40 THEN 'BLACKLIST' WHEN b0.v < 0.60 THEN 'SIMILARITY'
                                WHEN b0.v < 0.85 THEN 'ENGINE' ELSE 'CACHE' END
                       WHEN b0.u < 0.012 THEN NULL                   -- FAIL_CLOSE
                       WHEN b0.u < 0.014 THEN 'POLICY'               -- BYPASSED
                       ELSE                                         -- RELEASED: CACHE 70 / ENGINE 25 / WHITELIST 5
                           CASE WHEN b0.v < 0.70 THEN 'CACHE' WHEN b0.v < 0.95 THEN 'ENGINE' ELSE 'WHITELIST' END
                   END AS source
            FROM (
            SELECT md5('scale-agent-' || pc.i)::uuid AS agent_id, sd.kst_day, p.now, p.host_pool,
                   random() AS u_in, random() AS u_hour, random() AS u_tail, random() AS u_host,
                   random() AS u_sha, random() AS u_hold, random() AS u, random() AS v
            FROM scale_params p
            CROSS JOIN generate_series(1, p.pcs) pc(i)
            CROSS JOIN (SELECT * FROM scale_days WHERE ago = d_ago) sd
            -- PC x 날짜별 건수: per_day x 날짜 가중치의 정수부 + 소수부 확률
            CROSS JOIN LATERAL (SELECT floor(p.per_day * sd.weight + random())::int AS n) cnt
            CROSS JOIN LATERAL generate_series(1, cnt.n) e(i)
            ) b0
        ) b
    ) t, scale_params p

) x, scale_params xp
WHERE x.ts >= xp.now - make_interval(days => xp.days) AND x.ts < xp.now;  -- 구간 밖 시각은 버린다
END LOOP;
END $loop$;

ALTER TABLE download_events ENABLE TRIGGER bind_event_similarity;

-- SIMILARITY 차단의 근거: 이벤트 하나당 event_decision_evidence 1행(평가는 위에서 이벤트에 직접 연결했다)
INSERT INTO event_decision_evidence (event_id, agent_id, sha256, assessment_id, recorded_at)
SELECT event_id, agent_id, sha256, similarity_assessment_id, created_at
FROM download_events WHERE decision = 'BLOCKED' AND decision_source = 'SIMILARITY';

-- 6. 확인(acknowledgement): 7일보다 오래된 차단의 80%, 최근 차단의 30%. 로그인 못 하는 비활성 관리자 1명이 확인한 것으로 한다.
INSERT INTO admin_users (admin_id, username, password_hash, role, is_active)
VALUES (md5('scale-admin')::uuid, 'scale-seed-admin', '!', 'USER', false)
ON CONFLICT DO NOTHING;

INSERT INTO download_event_acknowledgements (event_id, acknowledged_by, acknowledged_at)
SELECT e.event_id, (SELECT admin_id FROM admin_users WHERE username = 'scale-seed-admin'),
       least(e.created_at + make_interval(secs => abs(h.v) % 172800), p.now)
FROM download_events e, scale_params p,
     LATERAL (SELECT hashtext(e.agent_id::text || e.created_at::text || coalesce(e.sha256, ''))::bigint AS v) h  -- 이벤트 내용으로 정한다: 같은 데이터면 같은 확인
WHERE e.decision IN ('BLOCKED', 'FAIL_CLOSE')
  AND (abs(h.v) % 1000) / 1000.0 < CASE WHEN e.created_at < p.now - interval '7 days' THEN 0.8 ELSE 0.3 END;

COMMIT;

-- 7. 통계 갱신(플래너가 큰 표 기준으로 계획을 세우게 한다)
ANALYZE download_events;
ANALYZE analyses;
ANALYZE analysis_matches;
ANALYZE file_verdicts;
ANALYZE download_event_acknowledgements;
ANALYZE event_decision_evidence;
ANALYZE similarity_assessments;
ANALYZE agents;
ANALYZE devices;
