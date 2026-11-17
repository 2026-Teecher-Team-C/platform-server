package com.teecherteamc.platform.dashboard.interfaces;

import com.teecherteamc.platform.common.ApiException;
import com.teecherteamc.platform.common.ErrorCode;
import com.teecherteamc.platform.dashboard.domain.DashboardPeriod;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * 대시보드 API 공통 기간 쿼리(from/to)를 {@link DashboardPeriod}로 바꾼다.
 * ISO-8601, [from, to), 생략 시 최근 7일, 최대 90일. 형식 오류·from >= to·90일 초과는 400(INVALID_INPUT).
 */
final class PeriodParams {

    private PeriodParams() {
    }

    static DashboardPeriod parse(String from, String to, Clock clock) {
        try {
            return DashboardPeriod.of(parseInstant(from, "from"), parseInstant(to, "to"), clock);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.INVALID_INPUT, e.getMessage());
        }
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
