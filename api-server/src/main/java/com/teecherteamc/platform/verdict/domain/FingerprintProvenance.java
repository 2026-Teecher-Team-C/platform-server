package com.teecherteamc.platform.verdict.domain;

/** file_fingerprints.provenance(V8)의 값. 이름이 DB 값과 같다. */
public enum FingerprintProvenance {
    /** 서버가 에이전트 업로드를 받아 SHA를 검증한 뒤 직접 계산한 지문. 기기가 실제로 받은 파일이다. */
    VERIFIED_UPLOAD,
    /** 관리자가 가져온 위협 피드 샘플의 지문. 기기가 받은 파일이 아니다. */
    ADMIN_IMPORT
}
