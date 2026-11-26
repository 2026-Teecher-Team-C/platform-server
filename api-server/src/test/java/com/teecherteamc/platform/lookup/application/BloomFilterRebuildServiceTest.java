package com.teecherteamc.platform.lookup.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.teecherteamc.platform.lookup.domain.BloomFillStats;
import com.teecherteamc.platform.lookup.domain.BloomFilterPort;
import com.teecherteamc.platform.lookup.domain.BloomFilterPort.BloomRebuild;
import com.teecherteamc.platform.lookup.domain.BloomRebuildResult;
import com.teecherteamc.platform.lookup.domain.CachedHashSourcePort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class BloomFilterRebuildServiceTest {

    private static final double TARGET_FPP = 0.01;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final CachedHashSourcePort source = mock(CachedHashSourcePort.class);
    private final BloomFilterPort port = mock(BloomFilterPort.class);
    private final BloomRebuild rebuild = mock(BloomRebuild.class);
    private final BloomRebuildRequests requests = new BloomRebuildRequests();
    private BloomFilterRebuildService service;

    @BeforeEach
    void setUp() {
        service = new BloomFilterRebuildService(
                source, port, requests, new BloomFilterMetrics(registry), TARGET_FPP);
        when(port.beginRebuild()).thenReturn(Optional.of(rebuild));
        when(source.findAllCachedHashes()).thenReturn(List.of("aa".repeat(32)));
        when(rebuild.complete(anyList())).thenReturn(new BloomRebuildResult(true, 10, 0));
    }

    @Test
    void 첫_예약_실행은_startup_다음은_daily() {
        service.scheduledRebuild();
        service.scheduledRebuild();

        assertThat(count(BloomRebuildRequests.STARTUP, "swapped")).isEqualTo(1.0);
        assertThat(count(BloomRebuildRequests.DAILY, "swapped")).isEqualTo(1.0);
    }

    @Test
    void 요청이_없으면_재구축하지_않는다() {
        service.runRequested();

        verify(port, never()).beginRebuild();
    }

    @Test
    void 요청이_여러_번이어도_한_번만_재구축한다() {
        requests.request(BloomRebuildRequests.MISSING);
        requests.request(BloomRebuildRequests.MISSING);
        requests.request(BloomRebuildRequests.ADD_FAILED);

        service.runRequested();
        service.runRequested();

        verify(port, times(1)).beginRebuild();
        assertThat(count(BloomRebuildRequests.MISSING, "swapped")).isEqualTo(1.0);
    }

    @Test
    void 이미_재구축_중이면_skipped로_센다() {
        when(port.beginRebuild()).thenReturn(Optional.empty());

        assertThat(service.rebuild(BloomRebuildRequests.MISSING)).isEmpty();

        assertThat(count(BloomRebuildRequests.MISSING, "skipped")).isEqualTo(1.0);
        verify(source, never()).findAllCachedHashes();
    }

    @Test
    void 교체를_포기하면_aborted로_세고_같은_사유로_다시_요청한다() {
        when(rebuild.complete(anyList())).thenReturn(BloomRebuildResult.notSwapped());

        service.rebuild(BloomRebuildRequests.ADD_FAILED);

        assertThat(count(BloomRebuildRequests.ADD_FAILED, "aborted")).isEqualTo(1.0);
        assertThat(requests.take()).contains(BloomRebuildRequests.ADD_FAILED);
    }

    @Test
    void 원천_조회가_실패하면_failed로_세고_다시_요청하며_pending을_닫는다() {
        when(source.findAllCachedHashes()).thenThrow(new IllegalStateException("db down"));

        assertThat(service.rebuild(BloomRebuildRequests.DAILY)).isEmpty();

        assertThat(count(BloomRebuildRequests.DAILY, "failed")).isEqualTo(1.0);
        verify(rebuild).close();
        assertThat(requests.take()).contains(BloomRebuildRequests.DAILY);
    }

    @Test
    void 교체되면_지운_비트와_채움_지표를_갱신한다() {
        when(rebuild.complete(anyList())).thenReturn(new BloomRebuildResult(true, 7, 3));
        when(port.fillStats()).thenReturn(Optional.of(new BloomFillStats(25, 100, 2)));

        assertThat(service.rebuild(BloomRebuildRequests.MISSING)).contains(new BloomRebuildResult(true, 7, 3));

        assertThat(gauge("lookup.bloom.rebuild.cleared.bits")).isEqualTo(3.0);
        assertThat(gauge("lookup.bloom.fill.ratio")).isEqualTo(0.25);
        assertThat(gauge("lookup.bloom.estimated.fpp")).isEqualTo(0.0625);
    }

    @Test
    void 추정_오탐률이_목표를_넘으면_WARN을_남긴다(CapturedOutput output) {
        // (50/100)^2 = 0.25 > 0.01
        when(port.fillStats()).thenReturn(Optional.of(new BloomFillStats(50, 100, 2)));

        service.refreshFill();

        assertThat(output.getOut()).contains("WARN").contains("블룸 추정 오탐률");
    }

    @Test
    void 추정_오탐률이_목표_이하면_WARN을_남기지_않는다(CapturedOutput output) {
        // (5/100)^2 = 0.0025 <= 0.01
        when(port.fillStats()).thenReturn(Optional.of(new BloomFillStats(5, 100, 2)));

        service.refreshFill();

        assertThat(output.getOut()).doesNotContain("블룸 추정 오탐률");
        assertThat(gauge("lookup.bloom.fill.ratio")).isEqualTo(0.05);
    }

    private double count(String reason, String outcome) {
        Counter counter = registry.find("lookup.bloom.rebuild")
                .tag("reason", reason)
                .tag("outcome", outcome)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    private double gauge(String name) {
        Gauge gauge = registry.find(name).gauge();
        return gauge == null ? Double.NaN : gauge.value();
    }
}
