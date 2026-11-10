package com.teecherteamc.platform.dashboard.application;

import com.teecherteamc.platform.dashboard.domain.UnacknowledgedBlockRow;
import com.teecherteamc.platform.dashboard.domain.UnacknowledgedBlocksPort;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * 대시보드 종 알림의 "확인 안 한 높은 심각도 차단" 목록. 배지 숫자는 totalElements를 쓴다.
 * page·size 범위 검증은 interfaces 계층이 한다.
 */
@Service
public class ListUnacknowledgedBlocksUseCase {

    private final UnacknowledgedBlocksPort port;

    public ListUnacknowledgedBlocksUseCase(UnacknowledgedBlocksPort port) {
        this.port = port;
    }

    public Page<UnacknowledgedBlockRow> list(int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size);
        List<UnacknowledgedBlockRow> rows = port.list(pageRequest.getOffset(), size);
        return new PageImpl<>(rows, pageRequest, port.count());
    }
}
