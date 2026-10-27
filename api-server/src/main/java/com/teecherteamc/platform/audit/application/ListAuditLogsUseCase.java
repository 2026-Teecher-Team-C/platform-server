package com.teecherteamc.platform.audit.application;

import com.teecherteamc.platform.audit.domain.AuditLogRow;
import com.teecherteamc.platform.audit.domain.ListAuditLogsPort;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * 관리자 콘솔의 감사 로그 목록 조회. 조회 로직은 포트의 SQL에 있고, 여기서는 한 페이지 분량의 행과
 * 전체 개수를 묶어 Page로 만든다. 필터·page·size 검증은 interfaces 계층이 한다.
 */
@Service
public class ListAuditLogsUseCase {

    private final ListAuditLogsPort listAuditLogsPort;

    public ListAuditLogsUseCase(ListAuditLogsPort listAuditLogsPort) {
        this.listAuditLogsPort = listAuditLogsPort;
    }

    public Page<AuditLogRow> list(ListAuditLogsPort.Filter filter, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size);
        List<AuditLogRow> rows = listAuditLogsPort.list(filter, pageRequest.getOffset(), size);
        return new PageImpl<>(rows, pageRequest, listAuditLogsPort.count(filter));
    }
}
