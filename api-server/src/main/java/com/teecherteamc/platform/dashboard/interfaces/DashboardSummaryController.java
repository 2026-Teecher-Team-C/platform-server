package com.teecherteamc.platform.dashboard.interfaces;

import com.teecherteamc.platform.dashboard.application.DashboardSummaryUseCase;
import com.teecherteamc.platform.dashboard.application.EngineStatusUseCase;
import com.teecherteamc.platform.dashboard.domain.DashboardSummary;
import com.teecherteamc.platform.dashboard.domain.EngineStatus;
import java.time.Clock;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 대시보드 상단 카운터와 탐지 엔진 상태 배지. 응답 모양은 콘솔 console/src/lib/api/dashboard.ts의
 * DashboardSummary·EngineStatus와 같다.
 */
@RestController
public class DashboardSummaryController {

    private final DashboardSummaryUseCase summaryUseCase;
    private final EngineStatusUseCase engineStatusUseCase;
    private final Clock clock;

    public DashboardSummaryController(
            DashboardSummaryUseCase summaryUseCase, EngineStatusUseCase engineStatusUseCase, Clock clock) {
        this.summaryUseCase = summaryUseCase;
        this.engineStatusUseCase = engineStatusUseCase;
        this.clock = clock;
    }

    @GetMapping("/api/admin/dashboard/summary")
    public DashboardSummary summary(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return summaryUseCase.summary(PeriodParams.parse(from, to, clock));
    }

    @GetMapping("/api/admin/dashboard/engine-status")
    public EngineStatus engineStatus() {
        return engineStatusUseCase.status(clock.instant());
    }
}
