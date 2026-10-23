package com.teecherteamc.platform.quarantine.domain;

import java.util.List;
import java.util.UUID;

/**
 * quarantine_files를 devices(호스트명)와 조인해 관리자 콘솔용 목록을 읽는 포트. 읽기 전용이라
 * QuarantinedFile 애그리거트를 안 거친다 — {@code event.domain.ListDownloadEventsPort}와 같은 이유.
 */
public interface ListQuarantineFilesPort {

    List<QuarantineListRow> list(String status, UUID agentId, long offset, int limit);

    /** list()와 같은 조건의 전체 행 수. 페이지 정보(totalElements)에 쓴다. */
    long count(String status, UUID agentId);
}
