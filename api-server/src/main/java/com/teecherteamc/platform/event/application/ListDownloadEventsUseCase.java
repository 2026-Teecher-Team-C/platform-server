package com.teecherteamc.platform.event.application;

import com.teecherteamc.platform.event.domain.DownloadEventListRow;
import com.teecherteamc.platform.event.domain.ListDownloadEventsPort;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 관리자 콘솔의 다운로드 이벤트 목록 조회. 포트가 조인까지 끝낸 행을 그대로 돌려주고, 이 유스케이스는
 * limit의 기본값/상한만 강제한다 — 그 외 조회 로직은 전부 포트의 SQL 한 줄짜리 쿼리에 있다.
 */
@Service
public class ListDownloadEventsUseCase {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final ListDownloadEventsPort listDownloadEventsPort;

    public ListDownloadEventsUseCase(ListDownloadEventsPort listDownloadEventsPort) {
        this.listDownloadEventsPort = listDownloadEventsPort;
    }

    public List<DownloadEventListRow> list(
            UUID agentId, String decision, Instant from, Instant to, Integer requestedLimit) {
        int limit = requestedLimit == null ? DEFAULT_LIMIT : Math.min(requestedLimit, MAX_LIMIT);
        return listDownloadEventsPort.list(agentId, decision, from, to, limit);
    }
}
