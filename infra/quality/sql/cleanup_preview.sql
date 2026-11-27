-- 정리 전에 지울 건수를 본다. 지우지 않는다.
SELECT 'event_decision_evidence', count(*) FROM event_decision_evidence WHERE agent_id = :'agent_id';
SELECT 'similarity_assessments', count(*) FROM similarity_assessments
    WHERE input_source = 'AGENT_CLAIMED'
      AND assessment_id IN (SELECT assessment_id FROM event_decision_evidence WHERE agent_id = :'agent_id');
SELECT 'download_events', count(*) FROM download_events WHERE agent_id = :'agent_id';
SELECT 'pending_quarantine_uploads', count(*) FROM pending_quarantine_uploads
    WHERE event_id IN (SELECT event_id FROM event_decision_evidence WHERE agent_id = :'agent_id');
