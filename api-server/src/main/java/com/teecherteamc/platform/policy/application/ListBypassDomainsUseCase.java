package com.teecherteamc.platform.policy.application;

import com.teecherteamc.platform.policy.domain.BypassDomainRow;
import com.teecherteamc.platform.policy.domain.ListBypassDomainsPort;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * 관리자 콘솔의 바이패스 도메인 목록 조회. 조회 로직은 전부 포트의 SQL에 있고, 이 유스케이스는
 * 한 페이지 분량의 행과 전체 개수를 묶어 Page로 만든다.
 * category·page·size 범위 검증은 interfaces 계층이 한다.
 */
@Service
public class ListBypassDomainsUseCase {

    private final ListBypassDomainsPort listBypassDomainsPort;

    public ListBypassDomainsUseCase(ListBypassDomainsPort listBypassDomainsPort) {
        this.listBypassDomainsPort = listBypassDomainsPort;
    }

    public Page<BypassDomainRow> list(Boolean active, String category, String hostKeyword, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size);
        List<BypassDomainRow> rows =
                listBypassDomainsPort.list(active, category, hostKeyword, pageRequest.getOffset(), size);
        long total = listBypassDomainsPort.count(active, category, hostKeyword);
        return new PageImpl<>(rows, pageRequest, total);
    }
}
