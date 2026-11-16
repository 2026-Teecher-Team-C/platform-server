-- V10의 revise_similarity_corpus()는 CHAR(64) 열(file_fingerprints.sha256, similarity_cache.sha256)을
-- TEXT 변수(key_sha)와 비교했다. 그러면 열 쪽이 text로 바뀌어 기본키 인덱스를 못 쓰고 표 전체를 읽는다.
-- 지문이 많아질수록 블랙리스트·화이트리스트 쓰기와 지문 쓰기가 한 건마다 표 전체를 읽게 되어(지문 4만 건에서
-- 순차 스캔 4만 번, 7.8억 행), 비교 쪽 변수를 bpchar로 바꿔 인덱스를 쓰게 한다. 동작(무엇을 바꾸는지)은 같다.
CREATE OR REPLACE FUNCTION revise_similarity_corpus() RETURNS trigger LANGUAGE plpgsql AS $$
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
            DELETE FROM similarity_cache WHERE sha256=key_sha::bpchar;
        END IF;
        changed := EXISTS(SELECT 1 FROM hash_blacklist WHERE hash_type='SHA256' AND hash_value=key_sha AND is_active);
        IF TG_OP = 'UPDATE' THEN
            changed := changed AND (OLD.tlsh, OLD.calculation_status, OLD.algorithm_version)
                IS DISTINCT FROM (NEW.tlsh, NEW.calculation_status, NEW.algorithm_version);
        END IF;
    ELSE
        key_sha := CASE WHEN TG_OP = 'DELETE' THEN OLD.hash_value ELSE NEW.hash_value END;
        -- 지문 없는 SHA와 설명/심각도 변경은 후보 집합을 바꾸지 않는다.
        changed := EXISTS(SELECT 1 FROM file_fingerprints WHERE sha256=key_sha::bpchar AND calculation_status='CALCULATED');
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
