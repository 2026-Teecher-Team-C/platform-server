package com.teecherteamc.platform.verdict.domain;

import java.nio.file.Path;

/**
 * SubmitFile이 MALICIOUS/SUSPICIOUS 판정을 받은 파일을 S3 격리 버킷에 올리는 포트. 실제
 * {@code quarantine_files} 행은 여기서 안 만든다 — event_id FK(download_events)가 이 시점엔
 * 아직 없어서, 업로드 결과만 {@link PendingQuarantineUploadPort}에 임시로 남겨둔다.
 */
public interface QuarantineUploadPort {

    QuarantineUploadResult upload(Path file);
}
