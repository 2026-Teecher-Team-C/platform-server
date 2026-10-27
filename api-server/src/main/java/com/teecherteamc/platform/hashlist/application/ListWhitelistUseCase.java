package com.teecherteamc.platform.hashlist.application;

import com.teecherteamc.platform.hashlist.domain.ListWhitelistPort;
import com.teecherteamc.platform.hashlist.domain.WhitelistRow;
import java.time.Clock;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * 관리자 콘솔의 화이트리스트 목록 조회. 조회 로직은 포트의 SQL에 있고, 여기서는 만료 기준 시각을 정하고
 * 한 페이지 분량의 행과 전체 개수를 묶어 Page로 만든다. 필터·page·size 검증은 interfaces 계층이 한다.
 */
@Service
public class ListWhitelistUseCase {

    private final ListWhitelistPort listWhitelistPort;
    private final Clock clock;

    public ListWhitelistUseCase(ListWhitelistPort listWhitelistPort, Clock clock) {
        this.listWhitelistPort = listWhitelistPort;
        this.clock = clock;
    }

    public Page<WhitelistRow> list(String hashPrefix, String origin, boolean active, boolean includeExpired,
                                   int page, int size) {
        ListWhitelistPort.Filter filter =
                new ListWhitelistPort.Filter(hashPrefix, origin, active, includeExpired, clock.instant());
        PageRequest pageRequest = PageRequest.of(page, size);
        List<WhitelistRow> rows = listWhitelistPort.list(filter, pageRequest.getOffset(), size);
        return new PageImpl<>(rows, pageRequest, listWhitelistPort.count(filter));
    }
}
