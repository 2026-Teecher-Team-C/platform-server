package com.teecherteamc.platform.hashlist.application;

import com.teecherteamc.platform.hashlist.domain.BlacklistListRow;
import com.teecherteamc.platform.hashlist.domain.ListBlacklistPort;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * 관리자 콘솔의 블랙리스트 목록 조회. 포트가 조인까지 끝낸 행을 그대로 돌려주고, 이 유스케이스는
 * 한 페이지 분량의 행과 전체 개수를 묶어 Page로 만든다 — 조회 로직은 전부 포트의 SQL에 있다.
 * 필터 값과 page·size 범위 검증은 interfaces 계층이 한다.
 */
@Service
public class ListBlacklistUseCase {

    private final ListBlacklistPort listBlacklistPort;

    public ListBlacklistUseCase(ListBlacklistPort listBlacklistPort) {
        this.listBlacklistPort = listBlacklistPort;
    }

    public Page<BlacklistListRow> list(
            String hashValuePrefix, String severity, String source, boolean active, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size);
        List<BlacklistListRow> rows = listBlacklistPort.list(
                hashValuePrefix, severity, source, active, pageRequest.getOffset(), size);
        long total = listBlacklistPort.count(hashValuePrefix, severity, source, active);
        return new PageImpl<>(rows, pageRequest, total);
    }
}