CREATE TABLE similarity_corpus_state (singleton BOOLEAN PRIMARY KEY DEFAULT true CHECK(singleton), revision BIGINT NOT NULL);
INSERT INTO similarity_corpus_state VALUES (true, 1);

-- 모든 목록 쓰기는 같은 전역 잠금을 먼저 얻는다. 비교/저장의 공유 잠금과 교차하여
-- 아직 INSERT되지 않은 캐시까지 누락시키는 '조회 후 무효화' 경쟁을 막는다.
CREATE FUNCTION lock_similarity_corpus() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(74190321);
    RETURN NULL;
END $$;
CREATE TRIGGER similarity_blacklist_lock BEFORE INSERT OR UPDATE OR DELETE ON hash_blacklist
    FOR EACH STATEMENT EXECUTE FUNCTION lock_similarity_corpus();
CREATE TRIGGER similarity_whitelist_lock BEFORE INSERT OR UPDATE OR DELETE ON hash_whitelist
    FOR EACH STATEMENT EXECUTE FUNCTION lock_similarity_corpus();
CREATE TRIGGER similarity_fingerprint_lock BEFORE INSERT OR UPDATE OR DELETE ON file_fingerprints
    FOR EACH STATEMENT EXECUTE FUNCTION lock_similarity_corpus();

CREATE FUNCTION revise_similarity_corpus() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE changed BOOLEAN := true; key_sha TEXT;
BEGIN
    IF TG_TABLE_NAME = 'file_fingerprints' THEN
        key_sha := CASE WHEN TG_OP = 'DELETE' THEN OLD.sha256 ELSE NEW.sha256 END;
        IF (TG_OP = 'INSERT' AND NEW.calculation_status = 'CALCULATED')
           OR (TG_OP = 'DELETE' AND OLD.calculation_status = 'CALCULATED')
           OR (TG_OP = 'UPDATE'
               AND (OLD.calculation_status = 'CALCULATED' OR NEW.calculation_status = 'CALCULATED')
               AND (OLD.tlsh, OLD.calculation_status, OLD.algorithm_version)
                   IS DISTINCT FROM (NEW.tlsh, NEW.calculation_status, NEW.algorithm_version)) THEN
            -- 산출 불가끼리의 보강은 비교 결과를 바꾸지 않는다. 새 유효 지문은 반드시 재평가한다.
            DELETE FROM similarity_cache WHERE sha256=key_sha;
        END IF;
        changed := EXISTS(SELECT 1 FROM hash_blacklist WHERE hash_type='SHA256' AND hash_value=key_sha AND is_active);
        IF TG_OP = 'UPDATE' THEN
            changed := changed AND (OLD.tlsh, OLD.calculation_status, OLD.algorithm_version)
                IS DISTINCT FROM (NEW.tlsh, NEW.calculation_status, NEW.algorithm_version);
        END IF;
    END IF;
    IF changed THEN UPDATE similarity_corpus_state SET revision=revision+1; END IF;
    RETURN NULL;
END $$;
CREATE TRIGGER similarity_blacklist_revision AFTER INSERT OR UPDATE OR DELETE ON hash_blacklist
    FOR EACH ROW EXECUTE FUNCTION revise_similarity_corpus();
CREATE TRIGGER similarity_whitelist_revision AFTER INSERT OR UPDATE OR DELETE ON hash_whitelist
    FOR EACH ROW EXECUTE FUNCTION revise_similarity_corpus();
CREATE TRIGGER similarity_fingerprint_revision AFTER INSERT OR UPDATE OR DELETE ON file_fingerprints
    FOR EACH ROW EXECUTE FUNCTION revise_similarity_corpus();

-- 만료는 UPDATE 없이도 후보를 바꾼다. 같은 트랜잭션의 now()를 뷰와 토큰 모두 사용한다.
CREATE VIEW current_similarity_corpus_token AS
SELECT revision::text || ':' || (SELECT count(*) FROM hash_whitelist
    WHERE is_active AND expires_at <= CURRENT_TIMESTAMP)::text || ':tlsh-30-70-v1' AS token
FROM similarity_corpus_state;
