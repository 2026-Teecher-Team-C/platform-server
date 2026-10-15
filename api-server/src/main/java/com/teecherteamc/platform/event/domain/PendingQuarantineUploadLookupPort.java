package com.teecherteamc.platform.event.domain;

import java.util.Optional;

/**
 * verdict 컨텍스트가 SubmitFile 때 남겨둔 S3 업로드 위치를 읽고 소비(삭제)하는 포트. 한 번만
 * 쓰인다 — ReportEvent가 quarantine_files 행을 만들 때 이 값을 쓰고 나면 다시 필요 없다.
 */
public interface PendingQuarantineUploadLookupPort {

    Optional<PendingQuarantineUpload> findAndDelete(String sha256);
}
