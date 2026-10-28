package com.teecherteamc.platform.fleet.application;

import com.teecherteamc.platform.fleet.domain.AgentListRow;
import com.teecherteamc.platform.fleet.domain.ListAgentsPort;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 에이전트 목록을 조회한다. (GET /api/admin/agents)
 * 읽기만 하고 아무것도 바꾸지 않는다. 필터 값 검증은 컨트롤러가 이미 끝냈다.
 */
@Service
public class ListAgentsUseCase {

    private final ListAgentsPort listAgentsPort;

    public ListAgentsUseCase(ListAgentsPort listAgentsPort) {
        this.listAgentsPort = listAgentsPort;
    }

    /**
     * 필터는 모두 선택이다. null이면 그 조건은 걸지 않는다.
     * page는 0부터 시작한다. (API 컨벤션: PageResponse)
     */
    @Transactional(readOnly = true)   // 읽기 전용 트랜잭션. list와 count가 같은 시점의 데이터를 본다.
    public Page<AgentListRow> list(String status, String hostname, String osPlatform, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);

        List<AgentListRow> rows = listAgentsPort.list(status, hostname, osPlatform, pageable.getOffset(), size);
        long total = listAgentsPort.count(status, hostname, osPlatform);

        // PageImpl: 이번 페이지 내용 + 전체 개수를 합쳐서 totalPages 등을 계산해 준다.
        return new PageImpl<>(rows, pageable, total);
    }
}