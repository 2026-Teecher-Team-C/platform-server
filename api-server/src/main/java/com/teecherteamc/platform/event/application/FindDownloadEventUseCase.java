package com.teecherteamc.platform.event.application;

import com.teecherteamc.platform.event.domain.DownloadEventDetailRow;
import com.teecherteamc.platform.event.domain.FindDownloadEventPort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 관리자 콘솔의 다운로드 이벤트 상세 조회. 포트가 조인까지 끝낸 행을 그대로 돌려준다 — 없을 때
 * 404로 바꾸는 건 HTTP 개념이라 interfaces 레이어(컨트롤러) 책임으로 남긴다.
 */
@Service
public class FindDownloadEventUseCase {

    private final FindDownloadEventPort findDownloadEventPort;

    public FindDownloadEventUseCase(FindDownloadEventPort findDownloadEventPort) {
        this.findDownloadEventPort = findDownloadEventPort;
    }

    public Optional<DownloadEventDetailRow> find(UUID eventId) {
        return findDownloadEventPort.findById(eventId);
    }
}
