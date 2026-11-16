package com.teecherteamc.platform.dashboard.application;

import com.teecherteamc.platform.dashboard.domain.DashboardPeriod;
import com.teecherteamc.platform.dashboard.domain.DashboardStatusPort;
import com.teecherteamc.platform.dashboard.domain.DashboardStatusPort.AnalysisMark;
import com.teecherteamc.platform.dashboard.domain.DashboardStatusPort.FailCloseImpact;
import com.teecherteamc.platform.dashboard.domain.DashboardSummary;
import com.teecherteamc.platform.dashboard.domain.EngineStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 대시보드 상단 카운터와 탐지 엔진 상태 배지(이슈 #79). 계산 기준은 콘솔 mock과 같다. */
@Service
public class DashboardStatusUseCase {

    static final int ENGINE_WINDOW_MINUTES = 10;

    private final DashboardStatusPort port;
    private final Clock clock;

    public DashboardStatusUseCase(DashboardStatusPort port, Clock clock) {
        this.port = port;
        this.clock = clock;
    }

    public DashboardSummary summary(DashboardPeriod period) {
        Duration length = Duration.between(period.from(), period.to());
        DashboardPeriod previous = new DashboardPeriod(period.from().minus(length), period.from());
        return DashboardSummary.of(period, port.counts(period), port.blockedCount(previous), port.activeAgents());
    }

    public EngineStatus engineStatus() {
        return engineStatus(clock.instant());
    }

    public EngineStatus engineStatus(Instant now) {
        Instant lastSuccess = port.lastSuccessAt(now).orElse(null);
        Instant lastFailure = port.lastFailureAt(now).orElse(null);
        Instant windowStart = now.minus(Duration.ofMinutes(ENGINE_WINDOW_MINUTES));
        Optional<AnalysisMark> latest = port.latestAnalysis(now)
                .filter(mark -> !mark.analyzedAt().isBefore(windowStart));
        if (latest.isEmpty()) {
            return new EngineStatus("IDLE", null, lastSuccess, lastFailure, ENGINE_WINDOW_MINUTES, 0, 0);
        }
        if (latest.get().success()) {
            return new EngineStatus("HEALTHY", null, lastSuccess, lastFailure, ENGINE_WINDOW_MINUTES, 0, 0);
        }
        // 가장 최근 기록이 실패이므로 lastSuccess 뒤 실패가 반드시 하나 이상 있다.
        Instant since = port.firstFailureAfter(lastSuccess, now).orElse(latest.get().analyzedAt());
        FailCloseImpact impact = port.failClosesBetween(since, now);
        return new EngineStatus("DOWN", since, lastSuccess, lastFailure, ENGINE_WINDOW_MINUTES,
                impact.events(), impact.agents());
    }
}
