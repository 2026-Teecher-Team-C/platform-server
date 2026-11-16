package com.teecherteamc.platform.verdict.domain;

import java.util.Map;
import java.util.UUID;

/** 지문 쓰기와 기준 샘플 자격의 읽기 경계, 다른 컨텍스트의 목록이나 계정을 변경하지 않는다. */
public interface FileFingerprintStore {
    void saveVerified(String sha256, FingerprintCalculation result);
    boolean lockActiveAdmin(UUID actor);
    boolean lockActiveBaseline(String sha256);
    boolean saveImported(String sha256, String tlsh, String provenance, UUID actor);

    /**
     * 활성 SHA256 블랙리스트인 SHA에만 지문을 일괄로 넣고, 실제로 쓴 행 수를 돌려준다.
     * 서버가 검증한 업로드 지문(CALCULATED)은 덮어쓰지 않고, 같은 가져오기 값이면 다시 쓰지 않는다(corpus 버전 유지).
     */
    int saveImportedBatch(Map<String, String> tlshBySha, String provenance, UUID actor);
}
