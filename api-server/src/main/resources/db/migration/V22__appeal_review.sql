-- 소명 검토(승인·거절).
-- 참고: V21 주석의 "이벤트 행 없이도 소명은 성립한다"는 폐기됐다. 제출은 그 에이전트의 BLOCKED 차단 기록을 확인한다.
-- 1) 승인으로 등록·재활성화된 화이트리스트가 어느 소명에서 왔는지 남긴다. 관리자가 직접 등록한 행은 NULL.
ALTER TABLE hash_whitelist
    ADD COLUMN origin_appeal_id UUID NULL,
    ADD CONSTRAINT fk_hash_whitelist_appeal FOREIGN KEY (origin_appeal_id) REFERENCES appeals (appeal_id);

-- 2) 제출 시 토큰의 판정 근거 스냅숏. 관리자 API에는 토큰이 없으므로 이 값으로 근거를 보여 준다.
--    V22 이전 소명은 NULL(콘솔은 "판정 근거 없음").
ALTER TABLE appeals
    ADD COLUMN evidence_kind VARCHAR(16)  NULL,   -- VerdictEvidence.Kind 이름(BLACKLIST·SIMILARITY·YARA·CACHE·NONE)
    ADD COLUMN threat        VARCHAR(255) NULL;   -- 토큰의 위협 이름(255자에서 자른다). 없으면 NULL
