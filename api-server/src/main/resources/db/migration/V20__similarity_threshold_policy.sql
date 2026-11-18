-- V19: TLSH 유사도 임계값을 코드 상수(30/70)에서 DB 설정으로 옮긴다.
-- 기본값은 기존 상수와 같으므로 이 마이그레이션만으로는 판정이 달라지지 않는다.
-- 뷰 본문은 V18의 current_similarity_corpus_token 정의 그대로이고, 마지막 정책 문자열만 함수 호출로 바꿨다.

-- 1. 임계값 저장 자리.
--    similarity_corpus_state는 1행 고정 테이블이고 토큰 뷰가 이미 이 행을 읽는다.
--    같은 행에 두면 "값 변경"과 "토큰 변경"이 한 번의 UPDATE로 묶인다.
ALTER TABLE similarity_corpus_state
    ADD COLUMN block_distance INTEGER NOT NULL DEFAULT 30,   -- 이 값 이하면 BLOCK
    ADD COLUMN warn_distance  INTEGER NOT NULL DEFAULT 70;   -- 이 값 이하(차단 초과)면 WARN

-- 2. 구조 제약만 DB에서 막는다. (0 이상, 경고 > 차단)
--    허용 범위의 상한은 정책이라 애플리케이션에서 검증한다. 상한을 바꿀 때 마이그레이션이 필요 없게 하려는 것이다.
ALTER TABLE similarity_corpus_state
    ADD CONSTRAINT ck_similarity_corpus_state_thresholds
    CHECK (block_distance >= 0 AND warn_distance > block_distance);

-- 3. 정책 문자열의 단일 출처. 기본값(30, 70)에서는 기존 'tlsh-30-70-v1'과 똑같이 나온다.
--    토큰 뷰와 similarity_assessments.policy_version 기록이 모두 이 함수를 쓴다.
CREATE FUNCTION similarity_policy_version(p_block INTEGER, p_warn INTEGER) RETURNS TEXT
    LANGUAGE sql IMMUTABLE STRICT AS $$
    SELECT 'tlsh-' || p_block::text || '-' || p_warn::text || '-v1'
$$;

-- 4. 토큰 뷰. 임계값이 바뀌면 토큰이 바뀌고, 그러면 캐시된 CLEAN/SIMILARITY 판정이 모두 stale이 된다.
CREATE OR REPLACE VIEW current_similarity_corpus_token AS
SELECT revision::text || ':' || generation::text || ':' || (SELECT count(*) FROM hash_whitelist
    WHERE is_active AND expires_at <= CURRENT_TIMESTAMP
      AND EXISTS(SELECT 1 FROM file_fingerprints f JOIN hash_blacklist b ON b.hash_value=f.sha256
          WHERE f.sha256=hash_whitelist.hash_value AND f.calculation_status='CALCULATED'
            AND b.hash_type='SHA256' AND b.is_active))::text
    || ':' || similarity_policy_version(block_distance, warn_distance) AS token
FROM similarity_corpus_state;