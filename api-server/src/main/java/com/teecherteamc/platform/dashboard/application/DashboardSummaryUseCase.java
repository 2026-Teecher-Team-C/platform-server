package com.teecherteamc.platform.dashboard.application;

import com.teecherteamc.platform.dashboard.domain.DashboardPeriod;
import com.teecherteamc.platform.dashboard.domain.DashboardSummary;
import com.teecherteamc.platform.dashboard.domain.DashboardSummaryPort;
import org.springframework.stereotype.Service;

/** 대시보드 상단 카운터. 기간 검증은 interfaces 계층(PeriodParams)과 DashboardPeriod가 한다. */
@Service
public class DashboardSummaryUseCase {

    private final DashboardSummaryPort port;

    public DashboardSummaryUseCase(DashboardSummaryPort port) {
        this.port = port;
    }

    public DashboardSummary summary(DashboardPeriod period) {
        return DashboardSummary.of(period, port.counts(period), port.totalAgents());
    }
}
