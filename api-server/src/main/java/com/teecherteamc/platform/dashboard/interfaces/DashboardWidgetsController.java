package com.teecherteamc.platform.dashboard.interfaces;

import com.teecherteamc.platform.common.ApiException;
import com.teecherteamc.platform.common.ErrorCode;
import com.teecherteamc.platform.dashboard.application.DashboardWidgetsUseCase;
import com.teecherteamc.platform.dashboard.domain.DailyBlocks;
import com.teecherteamc.platform.dashboard.domain.DashboardPeriod;
import com.teecherteamc.platform.dashboard.domain.DashboardWidgetsPort.SourceKind;
import com.teecherteamc.platform.dashboard.domain.ThreatRow;
import com.teecherteamc.platform.dashboard.domain.TopSourceRow;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 위협 대시보드 위젯 API. 응답 모양은 콘솔 console/src/lib/api/dashboard.ts의 ThreatRow·DailyBlocks·TopSource와 같다.
 * 기간은 from/to(ISO-8601, [from, to)), 생략하면 최근 7일, 최대 90일이다.
 */
@RestController
public class DashboardWidgetsController {

    private static final int DEFAULT_THREAT_SIZE = 10;
    private static final int MAX_THREAT_SIZE = 100;
    private static final int DEFAULT_SOURCE_SIZE = 5;
    // 위젯 등식 검증(합 = 차단 수)에 "제한 없음"이 필요해 넉넉히 둔다. 행 수는 PC·도메인 수를 넘지 않는다.
    private static final int MAX_SOURCE_SIZE = 1000;
    private static final String DEFAULT_TZ = "Asia/Seoul";

    private final DashboardWidgetsUseCase useCase;
    private final Clock clock;

    public DashboardWidgetsController(DashboardWidgetsUseCase useCase, Clock clock) {
        this.useCase = useCase;
        this.clock = clock;
    }

    public record ThreatsResponse(List<ThreatRow> content) {
    }

    public record TopSourcesResponse(String by, List<TopSourceRow> content) {
    }

    @GetMapping("/api/admin/dashboard/threats")
    public ThreatsResponse threats(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "" + DEFAULT_THREAT_SIZE) int size) {
        DashboardPeriod period = period(from, to);
        checkSize(size, MAX_THREAT_SIZE);
        return new ThreatsResponse(useCase.threats(period, size));
    }

    @GetMapping("/api/admin/dashboard/daily-blocks")
    public DailyBlocks dailyBlocks(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = DEFAULT_TZ) String tz,
            @RequestParam(defaultValue = "SOURCE") String stackBy) {
        DashboardPeriod period = period(from, to);
        ZoneId zone;
        try {
            zone = ZoneId.of(tz);
        } catch (DateTimeException e) {
            throw invalid("tz must be a valid IANA time zone");
        }
        return useCase.dailyBlocks(period, zone, parseEnum(DailyBlocks.StackBy.class, stackBy, "stackBy"));
    }

    @GetMapping("/api/admin/dashboard/top-sources")
    public TopSourcesResponse topSources(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "AGENT") String by,
            @RequestParam(defaultValue = "" + DEFAULT_SOURCE_SIZE) int size) {
        DashboardPeriod period = period(from, to);
        SourceKind kind = parseEnum(SourceKind.class, by, "by");
        checkSize(size, MAX_SOURCE_SIZE);
        return new TopSourcesResponse(kind.name(), useCase.topSources(period, kind, size));
    }

    private DashboardPeriod period(String from, String to) {
        try {
            return DashboardPeriod.of(parseInstant(from, "from"), parseInstant(to, "to"), clock);
        } catch (IllegalArgumentException e) {
            throw invalid(e.getMessage());
        }
    }

    private static Instant parseInstant(String value, String name) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw invalid(name + " must be an ISO-8601 timestamp");
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String name) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw invalid(name + " must be one of " + List.of(type.getEnumConstants()));
        }
    }

    private static void checkSize(int size, int max) {
        if (size < 1 || size > max) {
            throw invalid("size must be between 1 and " + max);
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.INVALID_INPUT, message);
    }
}
