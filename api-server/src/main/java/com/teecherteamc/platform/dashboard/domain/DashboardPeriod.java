package com.teecherteamc.platform.dashboard.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 대시보드 위젯 공통 기간 [from, to). 생략하면 최근 7일, 최대 90일이다.
 * 위반하면 {@link IllegalArgumentException}을 던지고 interfaces 계층이 400으로 바꾼다.
 */
public record DashboardPeriod(Instant from, Instant to) {

    public static final Duration DEFAULT_LENGTH = Duration.ofDays(7);
    public static final Duration MAX_LENGTH = Duration.ofDays(90);

    public DashboardPeriod {
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
        if (Duration.between(from, to).compareTo(MAX_LENGTH) > 0) {
            throw new IllegalArgumentException("period must be 90 days or shorter");
        }
    }

    /** 한쪽만 주면 다른 쪽을 7일 차이로 채운다. 둘 다 없으면 지금까지 최근 7일. */
    public static DashboardPeriod of(Instant from, Instant to, Clock clock) {
        if (from == null && to == null) {
            Instant now = clock.instant();
            return new DashboardPeriod(now.minus(DEFAULT_LENGTH), now);
        }
        if (from == null) {
            return new DashboardPeriod(to.minus(DEFAULT_LENGTH), to);
        }
        if (to == null) {
            return new DashboardPeriod(from, from.plus(DEFAULT_LENGTH));
        }
        return new DashboardPeriod(from, to);
    }
}
