-- SubmitFile이 S3에 올린 뒤, ReportEvent가 download_events를 만들 때까지 업로드 위치를 임시로
-- 들고 있는 테이블. quarantine_files.event_id가 NOT NULL FK라 SubmitFile 시점엔 그 행을 못
-- 만든다(download_events가 아직 없음 — ReportEvent가 그 이후에나 만든다). ReportEvent가 이
-- 테이블을 읽어서 최종 quarantine_files 행을 만들고 이 행은 지운다(한 번만 소비).
CREATE TABLE pending_quarantine_uploads (
    sha256      CHAR(64)      NOT NULL,
    file_size   BIGINT        NOT NULL,
    s3_bucket   VARCHAR(63)   NOT NULL,
    s3_key      VARCHAR(1024) NOT NULL,
    uploaded_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_pending_quarantine_uploads PRIMARY KEY (sha256),
    CONSTRAINT fk_pending_quarantine_uploads_sha256 FOREIGN KEY (sha256)
        REFERENCES file_verdicts (sha256)
);

COMMENT ON TABLE pending_quarantine_uploads IS 'SubmitFile의 S3 업로드와 ReportEvent의 quarantine_files 생성 사이를 잇는 임시 스테이징';
