-- 차단(BLOCKED) 이벤트 인덱스를 커버링 인덱스 하나로 합친다.
--
-- 왜: 미확인 차단 건수(unacknowledged-blocks count)가 PC 3천 대에서 8~10초 걸려 대시보드 화면 전체가 8초 안에
-- 뜨지 않았다(2026-11-28). 플래너가 V1의 idx_events_blocked(created_at DESC, BLOCKED 부분 인덱스)로 후보 8.6만 행을
-- 찾은 뒤 힙을 따로 읽었고, 실제로는 디스크 9.7만 블록을 읽었다. 플래너는 이 힙 읽기를 수천 블록으로 낮게 본다.
-- 그래서 V24처럼 옆에 커버링 인덱스를 하나 더 두면, 통계에 따라 플래너가 예전 인덱스를 다시 고른다
-- (V24 created_at 키, 그 뒤 시험한 (sha256, decision_source) 키 둘 다 데이터를 새로 만든 뒤 쓰이지 않았다).
--
-- 그래서 idx_events_blocked를 같은 키·같은 조건에 열을 더 담은 인덱스로 바꾼다. BLOCKED 행을 읽는 쿼리는 이제
-- 이 인덱스 하나만 고를 수 있고, 건수·위협 집계는 힙 없이 인덱스 전용 스캔이 된다. 키 순서(created_at DESC)는
-- 그대로라 최신순 목록(미확인 차단, 이벤트 목록)의 조기 종료도 그대로다.
-- 같은 DB(PC 3천 대)에서 ANALYZE를 세 번 다시 해도 계획이 같았다. 건수 9.9초 → 0.4~0.8초,
-- 위협 30일 0.67초 → 0.26초, 미확인 목록·차단 이벤트 목록은 2~6ms로 같거나 빨랐다.
DROP INDEX IF EXISTS idx_events_unacked_candidates;
DROP INDEX IF EXISTS idx_events_blocked;

CREATE INDEX idx_events_blocked_covering ON download_events (created_at DESC, event_id DESC)
    INCLUDE (agent_id, sha256, decision_source)
    WHERE decision = 'BLOCKED';
