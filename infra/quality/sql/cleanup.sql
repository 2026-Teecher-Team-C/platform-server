-- 측정 에이전트(:agent_id)가 만든 행을 외래 키 순서대로 한 트랜잭션에서 지우고, 에이전트를 REVOKED로 바꾼다.
-- 감사 로그는 남긴다(누가 언제 임계값·블랙리스트를 바꿨는지가 기록되어야 한다).
BEGIN;
CREATE TEMP TABLE q_events ON COMMIT DROP AS
    SELECT event_id, assessment_id FROM event_decision_evidence WHERE agent_id = :'agent_id';
DELETE FROM download_events WHERE agent_id = :'agent_id';
DELETE FROM pending_quarantine_uploads WHERE event_id IN (SELECT event_id FROM q_events);
DELETE FROM event_decision_evidence WHERE event_id IN (SELECT event_id FROM q_events);
DELETE FROM similarity_assessments
    WHERE input_source = 'AGENT_CLAIMED' AND assessment_id IN (SELECT assessment_id FROM q_events);
UPDATE agents SET status = 'REVOKED', updated_at = now() WHERE agent_id = :'agent_id';
COMMIT;
