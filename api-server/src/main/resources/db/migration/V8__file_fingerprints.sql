-- 판정 행보다 먼저 저장한다. FileVerdict FK를 두면 신규 업로드와 외부 지문 준비가 불가능하다.
CREATE TABLE file_fingerprints (
    sha256 CHAR(64) PRIMARY KEY CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    tlsh VARCHAR(72),
    calculation_status VARCHAR(24) NOT NULL
        CHECK (calculation_status IN ('CALCULATED', 'TOO_SHORT', 'LOW_ENTROPY', 'UNAVAILABLE')),
    algorithm_version VARCHAR(128) NOT NULL,
    calculated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    provenance VARCHAR(24) NOT NULL CHECK (provenance IN ('VERIFIED_UPLOAD', 'ADMIN_IMPORT')),
    provenance_detail VARCHAR(512),
    imported_by UUID REFERENCES admin_users(admin_id),
    CONSTRAINT ck_fingerprint_value CHECK (
        (calculation_status = 'CALCULATED' AND tlsh IS NOT NULL AND tlsh ~ '^T1[0-9A-F]{70}$')
        OR (calculation_status <> 'CALCULATED' AND tlsh IS NULL)),
    CONSTRAINT ck_fingerprint_import CHECK (
        provenance <> 'ADMIN_IMPORT' OR (imported_by IS NOT NULL AND provenance_detail IS NOT NULL))
);

-- 후보를 저장 당시의 활성 상태로 고정하지 않는다. 향후 비교는 이 읽기 전용 뷰를 사용한다.
-- 이번 단계에서는 뷰를 판정 경로에 연결하지 않으므로 가져오기만으로 차단이 생기지 않는다.
CREATE VIEW active_malicious_fingerprints AS
SELECT f.sha256, f.tlsh, f.algorithm_version, f.provenance
FROM file_fingerprints f
JOIN hash_blacklist b ON b.hash_type = 'SHA256' AND b.hash_value = f.sha256 AND b.is_active
WHERE f.calculation_status = 'CALCULATED'
  AND NOT EXISTS (
      SELECT 1 FROM hash_whitelist w
      WHERE w.hash_type = 'SHA256' AND w.hash_value = f.sha256 AND w.is_active
        AND (w.expires_at IS NULL OR w.expires_at > CURRENT_TIMESTAMP)
  );
