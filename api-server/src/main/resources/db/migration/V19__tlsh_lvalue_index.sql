-- TLSH 비교 후보를 L-value(파일 길이 구간)로 줄인다(고도화 테스트 mode=indexed, 2026-11-18).
-- 운영 비교 대상 117만 개를 요청마다 전부 읽어 CheckHash tlsh_compare p95가 약 10초였다(에이전트 RPC 제한 10초).
-- TLSH 거리의 길이 페널티는 원형 L 차이가 2 이상이면 차이×12라, 차이 6 이상(72)은 WARN 상한 70을 넘는다.
-- 그래서 입력 L ±5 후보만 비교해도 결론이 같다(TlshLValue, 동일성 테스트 TlshCompareModeEquivalenceIntegrationTest).
--
-- L-value: "T1" + checksum 2자 다음 2자를 니블 교환해 읽는다(TlshUtil.from_hex_swapped, 1-based 6번째·5번째 문자).
CREATE FUNCTION tlsh_lvalue(t text) RETURNS integer
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
    AS $$ SELECT (('x' || substr(t, 6, 1) || substr(t, 5, 1))::bit(8))::integer $$;

-- 활성 악성 후보 뷰(active_malicious_fingerprints)는 CALCULATED만 본다. 인덱스도 그 행만 담는다.
-- 일반 CREATE INDEX라 만드는 동안 file_fingerprints 쓰기만 잠깐 막고 읽기(CheckHash)는 막지 않는다.
CREATE INDEX idx_fingerprints_tlsh_lvalue ON file_fingerprints (tlsh_lvalue(tlsh))
    WHERE calculation_status = 'CALCULATED';
