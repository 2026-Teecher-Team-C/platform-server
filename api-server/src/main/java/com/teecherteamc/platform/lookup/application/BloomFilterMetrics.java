package com.teecherteamc.platform.lookup.application;

import com.teecherteamc.platform.lookup.domain.BloomFillStats;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * 블룸 재구축·채움 지표. 카운터 {@code lookup.bloom.rebuild{reason, outcome}}로 재구축이 왜·몇 번 돌았는지 보고,
 * 게이지 {@code lookup.bloom.fill.ratio}·{@code lookup.bloom.estimated.fpp}로 채움 상태를,
 * {@code lookup.bloom.rebuild.cleared.bits}로 마지막 교체가 지운 비트 수를 본다.
 */
@Component
public class BloomFilterMetrics {

    static final String REBUILD = "lookup.bloom.rebuild";
    static final String FILL_RATIO = "lookup.bloom.fill.ratio";
    static final String ESTIMATED_FPP = "lookup.bloom.estimated.fpp";
    static final String CLEARED_BITS = "lookup.bloom.rebuild.cleared.bits";

    public static final String SWAPPED = "swapped";
    public static final String SKIPPED = "skipped";
    public static final String ABORTED = "aborted";
    public static final String FAILED = "failed";

    private final MeterRegistry registry;
    private final AtomicReference<Double> fillRatio = new AtomicReference<>(0.0);
    private final AtomicReference<Double> estimatedFpp = new AtomicReference<>(0.0);
    private final AtomicLong clearedBits = new AtomicLong();

    public BloomFilterMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder(FILL_RATIO, fillRatio, AtomicReference::get).register(registry);
        Gauge.builder(ESTIMATED_FPP, estimatedFpp, AtomicReference::get).register(registry);
        Gauge.builder(CLEARED_BITS, clearedBits, AtomicLong::get).register(registry);
    }

    public void rebuilt(String reason, String outcome) {
        Counter.builder(REBUILD).tag("reason", reason).tag("outcome", outcome).register(registry).increment();
    }

    public void fill(BloomFillStats stats) {
        fillRatio.set(stats.fillRatio());
        estimatedFpp.set(stats.estimatedFpp());
    }

    public void cleared(long bits) {
        clearedBits.set(bits);
    }
}
