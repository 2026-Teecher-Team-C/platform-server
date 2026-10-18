package com.teecherteamc.platform.event.application;

import com.teecherteamc.platform.event.domain.DownloadEventListRow;
import com.teecherteamc.platform.event.domain.ListDownloadEventsPort;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * 관리자 콘솔의 다운로드 이벤트 목록 조회. 포트가 조인까지 끝낸 행을 그대로 돌려주고, 이 유스케이스는
 * 한 페이지 분량의 행과 전체 개수를 묶어 Page로 만든다 — 조회 로직은 전부 포트의 SQL에 있다.
 * page·size 범위 검증은 interfaces 계층이 한다.
 */
@Service
public class ListDownloadEventsUseCase {

    private final ListDownloadEventsPort listDownloadEventsPort;

    public ListDownloadEventsUseCase(ListDownloadEventsPort listDownloadEventsPort) {
        this.listDownloadEventsPort = listDownloadEventsPort;
    }

    public Page<DownloadEventListRow> list(
            UUID agentId, String decision, Instant from, Instant to, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size);
        List<DownloadEventListRow> rows = listDownloadEventsPort.list(
                agentId, decision, from, to, (int) pageRequest.getOffset(), size);
        long total = listDownloadEventsPort.count(agentId, decision, from, to);
        return new PageImpl<>(rows, pageRequest, total);
    }
}
