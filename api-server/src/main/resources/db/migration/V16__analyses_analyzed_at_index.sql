-- 대시보드 엔진 상태(GET /api/admin/dashboard/engine-status)가 상태별 가장 최근 분석을 찾는다.
-- 기존 idx_analyses_sha256(sha256, analyzed_at)은 sha256 조건 없이 쓸 수 없어 시각 인덱스를 따로 둔다.
CREATE INDEX idx_analyses_analyzed_at ON analyses (analyzed_at DESC);
