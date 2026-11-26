package com.teecherteamc.platform.appeal.application;

import com.teecherteamc.platform.appeal.domain.ListAppealsPort;
import com.teecherteamc.platform.event.domain.DownloadEventDetailRow;
import com.teecherteamc.platform.event.domain.EventSimilarity;
import com.teecherteamc.platform.event.domain.FindDownloadEventPort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 관리자 소명 상세. 유사도는 다운로드 이벤트 상세와 같은 {@link FindDownloadEventPort}로 읽는다
 * (이벤트 FK에 연결된 당시 평가만 공개). 이벤트 행이 없으면 유사도는 null이다.
 */
@Service
public class GetAppealDetailUseCase {

    private final ListAppealsPort listAppealsPort;
    private final FindDownloadEventPort findDownloadEventPort;

    public GetAppealDetailUseCase(ListAppealsPort listAppealsPort, FindDownloadEventPort findDownloadEventPort) {
        this.listAppealsPort = listAppealsPort;
        this.findDownloadEventPort = findDownloadEventPort;
    }

    public Optional<AdminAppealDetail> get(UUID appealId) {
        return listAppealsPort.findById(appealId).map(row -> {
            EventSimilarity similarity = findDownloadEventPort.findById(row.eventId())
                    .map(DownloadEventDetailRow::similarity).orElse(null);
            return AdminAppealDetail.from(row, similarity);
        });
    }
}
