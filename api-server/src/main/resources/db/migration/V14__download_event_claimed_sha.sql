-- 업로드 없이 CheckHash 단계 TLSH로 차단된 이벤트는 file_verdicts 행이 없다. 그래도 어떤 파일이 막혔는지(sha256)와
-- 근거(event_decision_evidence 연결)를 이벤트에 남겨야 하므로 download_events.sha256 FK를 푼다.
-- file_verdicts에 행이 없는 화이트리스트/바이패스 이벤트는 ReportEvent가 계속 NULL로 저장한다.
ALTER TABLE download_events DROP CONSTRAINT fk_download_events_sha256;
