ALTER TABLE file_verdicts DROP CONSTRAINT ck_file_verdicts_source;
ALTER TABLE file_verdicts ADD CONSTRAINT ck_file_verdicts_source
    CHECK (verdict_source IN ('WHITELIST','BLACKLIST','ENGINE','MANUAL','SIMILARITY'));
ALTER TABLE download_events DROP CONSTRAINT ck_download_events_decision_source;
ALTER TABLE download_events ADD CONSTRAINT ck_download_events_decision_source
    CHECK (decision_source IN ('WHITELIST','BLACKLIST','CACHE','ENGINE','POLICY','SIMILARITY'));

-- 엔진 원본은 analyses에, 추가 정책과 당시 기준 샘플은 불변 근거에 분리한다.
CREATE TABLE similarity_assessments (
    assessment_id UUID PRIMARY KEY,
    sha256 CHAR(64) NOT NULL REFERENCES file_verdicts(sha256),
    analysis_id UUID REFERENCES analyses(analysis_id),
    corpus_token TEXT NOT NULL,
    policy_version VARCHAR(32) NOT NULL,
    matched_sample_sha256 CHAR(64),
    matched_sample_tlsh VARCHAR(72),
    distance INTEGER CHECK (distance >= 0),
    classification VARCHAR(16) NOT NULL CHECK (classification IN ('BLOCK','WARN','NONE','UNAVAILABLE')),
    assessed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK ((distance IS NULL AND matched_sample_sha256 IS NULL AND matched_sample_tlsh IS NULL)
        OR (distance IS NOT NULL AND matched_sample_sha256 IS NOT NULL AND matched_sample_tlsh IS NOT NULL))
);
-- ReportEvent 이전에도 기록 가능하다. SHA의 최신 분석이 아니라 요청 UUID에 묶는다.
CREATE TABLE event_decision_evidence (
    event_id UUID PRIMARY KEY,
    agent_id UUID NOT NULL REFERENCES agents(agent_id),
    sha256 CHAR(64),
    assessment_id UUID REFERENCES similarity_assessments(assessment_id),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE similarity_cache (
    sha256 CHAR(64) PRIMARY KEY REFERENCES file_verdicts(sha256),
    assessment_id UUID NOT NULL REFERENCES similarity_assessments(assessment_id)
);
