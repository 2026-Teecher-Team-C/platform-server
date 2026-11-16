-- 에이전트가 주장한 TLSH로 만든 평가는 업로드 전이라 file_verdicts 행이 없을 수 있다.
-- input_source로 검증/주장을 구분하고, similarity_assessments의 sha256 FK만 푼다. similarity_cache의 FK는 유지한다.
ALTER TABLE similarity_assessments
    ADD COLUMN input_source VARCHAR(16) NOT NULL DEFAULT 'VERIFIED'
        CHECK (input_source IN ('VERIFIED','AGENT_CLAIMED'));
ALTER TABLE similarity_assessments DROP CONSTRAINT similarity_assessments_sha256_fkey;
