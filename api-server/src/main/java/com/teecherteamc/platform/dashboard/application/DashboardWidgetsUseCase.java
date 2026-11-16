package com.teecherteamc.platform.dashboard.application;

import com.teecherteamc.platform.dashboard.domain.DailyBlocks;
import com.teecherteamc.platform.dashboard.domain.DashboardPeriod;
import com.teecherteamc.platform.dashboard.domain.DashboardWidgetsPort;
import com.teecherteamc.platform.dashboard.domain.DashboardWidgetsPort.SourceKind;
import com.teecherteamc.platform.dashboard.domain.ThreatRow;
import com.teecherteamc.platform.dashboard.domain.TopSourceRow;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 위협 대시보드 위젯: 위협별 현황, 일별 차단, 차단 상위 PC·도메인(이슈 #80, #82).
 * 위젯끼리 숫자가 맞도록 차단 정의(BLOCKED·FAIL_CLOSE)와 조인은 포트 한 곳에 둔다. 파라미터 검증은 interfaces 계층이 한다.
 */
@Service
public class DashboardWidgetsUseCase {

    private final DashboardWidgetsPort port;

    public DashboardWidgetsUseCase(DashboardWidgetsPort port) {
        this.port = port;
    }

    public List<ThreatRow> threats(DashboardPeriod period, int size) {
        return port.threats(period, size);
    }

    public DailyBlocks dailyBlocks(DashboardPeriod period, ZoneId zone, DailyBlocks.StackBy stackBy) {
        return DailyBlocks.compute(port.blocks(period), period, zone, stackBy);
    }

    public List<TopSourceRow> topSources(DashboardPeriod period, SourceKind by, int size) {
        return port.topSources(period, by, size);
    }
}
