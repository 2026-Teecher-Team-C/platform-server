package com.teecherteamc.platform.dashboard.interfaces;

import com.teecherteamc.platform.common.ApiException;
import com.teecherteamc.platform.common.ErrorCode;
import com.teecherteamc.platform.dashboard.application.DashboardStatusUseCase;
import com.teecherteamc.platform.dashboard.domain.DashboardPeriod;
import com.teecherteamc.platform.dashboard.domain.DashboardSummary;
import com.teecherteamc.platform.dashboard.domain.EngineStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 대시보드 상단 카운터와 탐지 엔진 상태 배지. 응답 모양은 콘솔 DashboardSummary·EngineStatus 타입과 같다. */
@RestController
public class DashboardStatusController {

    private final DashboardStatusUseCase useCase;
    private final Clock clock;

    public DashboardStatusController(DashboardStatusUseCase useCase, Clock clock) {
        this.useCase = useCase;
        this.clock = clock;
    }

    @GetMapping("/api/admin/dashboard/summary")
    public DashboardSummary summary(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        try {
            return useCase.summary(DashboardPeriod.of(parseInstant(from, "from"), parseInstant(to, "to"), clock));
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.INVALID_INPUT, e.getMessage());
        }
    }

    @GetMapping("/api/admin/dashboard/engine-status")
    public EngineStatus engineStatus() {
        return useCase.engineStatus();
    }

    private static Instant parseInstant(String value, String name) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new ApiException(ErrorCode.INVALID_INPUT, name + " must be an ISO-8601 timestamp");
        }
    }
}
