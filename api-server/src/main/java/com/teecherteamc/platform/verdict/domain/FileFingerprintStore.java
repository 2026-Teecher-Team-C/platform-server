package com.teecherteamc.platform.verdict.domain;

import java.util.UUID;

/** 지문 쓰기와 기준 샘플 자격의 읽기 경계, 다른 컨텍스트의 목록이나 계정을 변경하지 않는다. */
public interface FileFingerprintStore {
    void saveVerified(String sha256, FingerprintCalculation result);
    boolean lockActiveAdmin(UUID actor);
    boolean lockActiveBaseline(String sha256);
    boolean saveImported(String sha256, String tlsh, String provenance, UUID actor);
}
