package com.teecherteamc.platform.lookup.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * CheckHash 지연 지표(고도화 테스트 1-1). 단계 타이머 {@code verdict.checkhash.stage{stage}}와 전체 타이머
 * {@code verdict.checkhash{decision,source}}. 히스토그램·SLO 버킷은 application.yml에서 붙인다.
 */
@Component
public class CheckHashMetrics {

    static final String STAGE = "verdict.checkhash.stage";
    static final String TOTAL = "verdict.checkhash";

    private final MeterRegistry registry;

    public CheckHashMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public <T> T stage(String stage, Supplier<T> work) {
        return Timer.builder(STAGE).tag("stage", stage).register(registry).record(work);
    }

    public void stage(String stage, Runnable work) {
        Timer.builder(STAGE).tag("stage", stage).register(registry).record(work);
    }

    public void record(long startNanos, String decision, String source) {
        Timer.builder(TOTAL)
                .tag("decision", decision)
                .tag("source", source == null ? "NONE" : source)
                .register(registry)
                .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }
}
