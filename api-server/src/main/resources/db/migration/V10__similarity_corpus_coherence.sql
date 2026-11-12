-- Java와 트리거가 같은 DB 함수를 호출한다. Java 상수를 SQL이 사용한다고 가정하지 않는다.
CREATE FUNCTION similarity_lock_key(scope TEXT) RETURNS INTEGER LANGUAGE plpgsql IMMUTABLE STRICT AS $$
BEGIN
    CASE scope
        WHEN 'corpus' THEN RETURN 74190321;
        WHEN 'file' THEN RETURN 74190322;
        WHEN 'event' THEN RETURN 74190323;
        WHEN 'eligibility' THEN RETURN 74190324;
        ELSE RAISE EXCEPTION 'Unknown similarity lock scope: %', scope;
    END CASE;
END $$;

CREATE TABLE similarity_corpus_state (singleton BOOLEAN PRIMARY KEY DEFAULT true CHECK(singleton), revision BIGINT NOT NULL, generation UUID NOT NULL DEFAULT gen_random_uuid());
INSERT INTO similarity_corpus_state(singleton, revision) VALUES (true, 1);

-- 목록과 지문은 SHA gate를 먼저 잡는다. 아직 지문/기준 행이 없는 동시 INSERT도 누락하지 않는다.
-- 일반 지문 쓰기는 전역 락 없이 파일 락만 획득, 활성 기준/관리자 import만 corpus를 배타 보호한다.
CREATE FUNCTION lock_similarity_corpus() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE key_sha TEXT; baseline BOOLEAN;
BEGIN
    IF TG_TABLE_NAME='file_fingerprints' THEN
        key_sha := CASE WHEN TG_OP='DELETE' THEN OLD.sha256 ELSE NEW.sha256 END;
    ELSE
        key_sha := CASE WHEN TG_OP='DELETE' THEN OLD.hash_value ELSE NEW.hash_value END;
    END IF;
    PERFORM pg_advisory_xact_lock(similarity_lock_key('eligibility'), hashtext(key_sha));
    IF TG_TABLE_NAME='file_fingerprints' THEN
        baseline := EXISTS(SELECT 1 FROM hash_blacklist WHERE hash_type='SHA256' AND hash_value=key_sha AND is_active);
        IF baseline OR (TG_OP <> 'DELETE' AND NEW.provenance='ADMIN_IMPORT')
                    OR (TG_OP <> 'INSERT' AND OLD.provenance='ADMIN_IMPORT') THEN
            PERFORM pg_advisory_xact_lock(similarity_lock_key('corpus')::bigint);
        END IF;
        PERFORM pg_advisory_xact_lock(similarity_lock_key('file'), hashtext(key_sha));
    ELSE
        -- 정확 SHA 예외도 비교/저장과 교차하므로 목록 쓰기 자체는 전역 경계에 참여한다.
        PERFORM pg_advisory_xact_lock(similarity_lock_key('corpus')::bigint);
    END IF;
    RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END $$;
CREATE TRIGGER similarity_blacklist_lock BEFORE INSERT OR UPDATE OR DELETE ON hash_blacklist
    FOR EACH ROW EXECUTE FUNCTION lock_similarity_corpus();
CREATE TRIGGER similarity_whitelist_lock BEFORE INSERT OR UPDATE OR DELETE ON hash_whitelist
    FOR EACH ROW EXECUTE FUNCTION lock_similarity_corpus();
CREATE TRIGGER similarity_fingerprint_lock BEFORE INSERT OR UPDATE OR DELETE ON file_fingerprints
    FOR EACH ROW EXECUTE FUNCTION lock_similarity_corpus();

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
    ELSE
        key_sha := CASE WHEN TG_OP = 'DELETE' THEN OLD.hash_value ELSE NEW.hash_value END;
        -- 지문 없는 SHA와 설명/심각도 변경은 후보 집합을 바꾸지 않는다.
        changed := EXISTS(SELECT 1 FROM file_fingerprints WHERE sha256=key_sha AND calculation_status='CALCULATED');
        IF TG_TABLE_NAME = 'hash_blacklist' THEN
            changed := changed AND CASE WHEN TG_OP='INSERT' THEN NEW.hash_type='SHA256' AND NEW.is_active
                         WHEN TG_OP='DELETE' THEN OLD.hash_type='SHA256' AND OLD.is_active
                         ELSE (OLD.hash_type, OLD.hash_value, OLD.is_active) IS DISTINCT FROM
                              (NEW.hash_type, NEW.hash_value, NEW.is_active) END;
        ELSE
            changed := changed AND EXISTS(SELECT 1 FROM hash_blacklist WHERE hash_type='SHA256' AND hash_value=key_sha AND is_active)
                AND CASE WHEN TG_OP='INSERT' THEN NEW.is_active
                         WHEN TG_OP='DELETE' THEN OLD.is_active
                         ELSE (OLD.hash_value, OLD.is_active, OLD.expires_at) IS DISTINCT FROM
                              (NEW.hash_value, NEW.is_active, NEW.expires_at) END;
        END IF;
    END IF;
    IF changed THEN UPDATE similarity_corpus_state SET revision=revision+1, generation=gen_random_uuid(); END IF;
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
SELECT revision::text || ':' || generation::text || ':' || (SELECT count(*) FROM hash_whitelist
    WHERE is_active AND expires_at <= CURRENT_TIMESTAMP
      AND EXISTS(SELECT 1 FROM file_fingerprints f JOIN hash_blacklist b ON b.hash_value=f.sha256
          WHERE f.sha256=hash_whitelist.hash_value AND f.calculation_status='CALCULATED'
            AND b.hash_type='SHA256' AND b.is_active))::text || ':tlsh-30-70-v1' AS token
FROM similarity_corpus_state;
