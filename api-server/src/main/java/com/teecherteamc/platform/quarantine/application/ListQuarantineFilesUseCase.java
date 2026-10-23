package com.teecherteamc.platform.quarantine.application;

import com.teecherteamc.platform.quarantine.domain.ListQuarantineFilesPort;
import com.teecherteamc.platform.quarantine.domain.QuarantineListRow;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * 관리자 콘솔의 격리 파일 목록 조회. 포트가 조인까지 끝낸 행을 그대로 돌려주고, 이 유스케이스는
 * 한 페이지 분량의 행과 전체 개수를 묶어 Page로 만든다 — 조회 로직은 전부 포트의 SQL에 있다.
 * status·page·size 범위 검증은 interfaces 계층이 한다.
 */
@Service
public class ListQuarantineFilesUseCase {

    private final ListQuarantineFilesPort listQuarantineFilesPort;

    public ListQuarantineFilesUseCase(ListQuarantineFilesPort listQuarantineFilesPort) {
        this.listQuarantineFilesPort = listQuarantineFilesPort;
    }

    public Page<QuarantineListRow> list(String status, UUID agentId, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size);
        List<QuarantineListRow> rows =
                listQuarantineFilesPort.list(status, agentId, pageRequest.getOffset(), size);
        long total = listQuarantineFilesPort.count(status, agentId);
        return new PageImpl<>(rows, pageRequest, total);
    }
}
