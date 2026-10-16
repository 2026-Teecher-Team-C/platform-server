package com.teecherteamc.platform.verdict.domain;

/**
 * S3 업로드 위치를 ReportEvent가 quarantine_files 행을 만들 때까지 임시로 적어두는 포트. 쓰기
 * 전용이다 — 읽고 지우는 건 event 컨텍스트가 자기 포트로 한다(컨텍스트 간 참조는 소비하는 쪽이
 * 얇은 포트를 직접 정의하는 기존 컨벤션, FileVerdictExistsPort와 동일).
 */
public interface PendingQuarantineUploadPort {

    void save(String sha256, long fileSize, String bucket, String key);
}
