package com.teecherteamc.platform.quarantine.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * quarantine_files를 devices(호스트명)·download_events(요청 URL)와 조인해 단건 상세를 읽는 포트.
 * {@link ListQuarantineFilesPort}와 같은 이유로 읽기 전용이라 애그리거트를 안 거친다.
 */
public interface FindQuarantineFilePort {

    Optional<QuarantineDetailRow> findById(UUID quarantineId);
}
