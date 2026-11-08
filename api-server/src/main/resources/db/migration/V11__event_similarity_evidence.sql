-- 신규 SubmitFile 격리는 SHA만이 아니라 인증 소유권이 예약된 이벤트에 귀속된다.
-- NULL event_id는 기존 내부 호출/옛 pending 행만을 위한 호환 경로다.
ALTER TABLE pending_quarantine_uploads ADD COLUMN event_id UUID REFERENCES event_decision_evidence(event_id);
ALTER TABLE pending_quarantine_uploads DROP CONSTRAINT pk_pending_quarantine_uploads;
ALTER TABLE pending_quarantine_uploads ADD CONSTRAINT uq_pending_quarantine_event UNIQUE(event_id);
CREATE UNIQUE INDEX uq_pending_quarantine_legacy_sha ON pending_quarantine_uploads(sha256) WHERE event_id IS NULL;

ALTER TABLE download_events ADD COLUMN similarity_assessment_id UUID REFERENCES similarity_assessments(assessment_id);
-- 캐시 조회만 했던 옛 에이전트의 이벤트는 매핑이 없으므로 NULL이다. 최신 SHA 분석으로 추측하지 않는다.
CREATE FUNCTION bind_event_similarity_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    SELECT e.assessment_id INTO NEW.similarity_assessment_id
    FROM event_decision_evidence e JOIN similarity_assessments a USING(assessment_id)
    WHERE e.event_id=NEW.event_id AND e.sha256=NEW.sha256 AND e.agent_id=NEW.agent_id
      AND ((NEW.decision='BLOCKED' AND NEW.decision_source IN ('SIMILARITY','CACHE') AND a.classification='BLOCK')
        OR (NEW.decision='RELEASED' AND NEW.decision_source IN ('ENGINE','CACHE') AND a.classification<>'BLOCK'));
    RETURN NEW;
END $$;
CREATE TRIGGER bind_event_similarity BEFORE INSERT ON download_events
    FOR EACH ROW EXECUTE FUNCTION bind_event_similarity_evidence();
