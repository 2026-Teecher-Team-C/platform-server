-- 1단계 대시보드 인덱스 개선:
-- 1) 기존 idx_events_block_created_at에 request_host를 포함해 top-sources-host까지 인덱스 전용 스캔으로 처리한다.
-- 2) unacknowledged-blocks용 부분 커버링 인덱스: BLOCKED + ENGINE/CACHE/BLACKLIST만 최신순으로 담는다.
DROP INDEX IF EXISTS idx_events_block_created_at;

CREATE INDEX idx_events_block_created_at ON download_events (created_at)
    INCLUDE (agent_id, request_host)
    WHERE decision IN ('BLOCKED', 'FAIL_CLOSE');

CREATE INDEX idx_events_unacked_candidates ON download_events (created_at DESC, event_id DESC)
    INCLUDE (agent_id, sha256, decision_source)
    WHERE decision = 'BLOCKED' AND decision_source IN ('ENGINE', 'CACHE', 'BLACKLIST');
