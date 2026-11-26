package com.teecherteamc.platform.lookup.application;

import com.teecherteamc.platform.lookup.domain.BloomFillStats;
import com.teecherteamc.platform.lookup.domain.BloomFilterPort;
import com.teecherteamc.platform.lookup.domain.BloomFilterPort.BloomRebuild;
import com.teecherteamc.platform.lookup.domain.BloomRebuildResult;
import com.teecherteamc.platform.lookup.domain.CachedHashSourcePort;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 블룸 필터는 항목을 삭제할 수 없어서, 원천에서 빠진 해시를 치우는 방법은 전체 재구축뿐이다. 평소엔 쓸 때 바로 추가하므로
 * 재구축은 필요할 때만 돈다: 기동 직후 한 번(startup), 안전망으로 하루 한 번(daily), 그리고 키 없음·추가 실패 등으로
 * {@link BloomRebuildRequests}에 요청이 걸렸을 때(폴링). 같은 시각에 재구축은 하나만 돈다(pending 표시).
 *
 * <p>file_verdicts 쪽 누락은 안전한 방향으로만 틀린다(캐시 미스로 엔진까지 갈 뿐 잘못된 ALLOW는 없음). 목록(블랙/화이트)
 * 쪽은 블룸 미스가 정확 일치 조회를 생략시키므로 누락되면 안 된다. 보장: 목록 쓰기는 커밋 뒤에 add하고, 재구축은 표시
 * 키(pending)를 세운 <b>뒤에</b> 스냅숏을 읽는다. 표시 전에 커밋된 행은 스냅숏에 있고, 표시 뒤의 add는 pending에도
 * 쓰여 교체 때 합쳐진다.
 */
@Service
public class BloomFilterRebuildService {

    private static final Logger log = LoggerFactory.getLogger(BloomFilterRebuildService.class);

    private final CachedHashSourcePort cachedHashSourcePort;
    private final BloomFilterPort bloomFilterPort;
    private final BloomRebuildRequests requests;
    private final BloomFilterMetrics metrics;
    private final double targetFpp;
    private final AtomicBoolean startupDone = new AtomicBoolean();

    public BloomFilterRebuildService(
            CachedHashSourcePort cachedHashSourcePort,
            BloomFilterPort bloomFilterPort,
            BloomRebuildRequests requests,
            BloomFilterMetrics metrics,
            @Value("${lookup.bloom-filter.false-positive-rate}") double targetFpp) {
        this.cachedHashSourcePort = cachedHashSourcePort;
        this.bloomFilterPort = bloomFilterPort;
        this.requests = requests;
        this.metrics = metrics;
        this.targetFpp = targetFpp;
    }

    /** 기동 직후 한 번(startup), 그 뒤 rebuild-interval-ms마다(daily) 도는 안전망 재구축. */
    @Scheduled(fixedRateString = "${lookup.bloom-filter.rebuild-interval-ms}", initialDelay = 0)
    public void scheduledRebuild() {
        rebuild(startupDone.compareAndSet(false, true) ? BloomRebuildRequests.STARTUP : BloomRebuildRequests.DAILY);
    }

    /**
     * 걸린 재구축 요청이 있으면 그 사유로 한 번 재구축한다. 요청이 여러 번 쌓였어도 한 번이다. initialDelay를 주기와 같게
     * 둬서 기동 직후의 startup 재구축과 겹치지 않고, 긴 값을 주면(테스트) 사실상 꺼진다.
     */
    @Scheduled(
            fixedDelayString = "${lookup.bloom-filter.request-poll-ms}",
            initialDelayString = "${lookup.bloom-filter.request-poll-ms}")
    public void runRequested() {
        requests.take().ifPresent(this::rebuild);
    }

    /** 채움 게이지를 갱신하고, 추정 오탐률이 목표를 넘으면 WARN을 남긴다. */
    @Scheduled(
            fixedDelayString = "${lookup.bloom-filter.fill-check-ms}",
            initialDelayString = "${lookup.bloom-filter.fill-check-ms}")
    public void refreshFill() {
        Optional<BloomFillStats> stats;
        try {
            stats = bloomFilterPort.fillStats();
        } catch (RuntimeException e) {
            log.warn("블룸 채움 통계 조회 실패", e);
            return;
        }
        stats.ifPresent(s -> {
            metrics.fill(s);
            if (s.estimatedFpp() > targetFpp) {
                log.warn("블룸 추정 오탐률 {} > 목표 {} — expected-hash-count를 올리고 재시작", s.estimatedFpp(), targetFpp);
            }
        });
    }

    /**
     * 재구축 한 번. 다른 재구축이 이미 돌고 있으면 시작하지 않고 empty를 준다(skipped). 실패는 던지지 않고 로그와 재요청으로
     * 처리한다(스케줄러 스레드 보호): 예외면 failed, 교체를 포기했으면(pending 사라짐) aborted — 둘 다 같은 사유로 다시 요청한다.
     */
    public Optional<BloomRebuildResult> rebuild(String reason) {
        long start = System.nanoTime();
        try {
            Optional<BloomRebuild> started = bloomFilterPort.beginRebuild();
            if (started.isEmpty()) {
                metrics.rebuilt(reason, BloomFilterMetrics.SKIPPED);
                log.info("블룸 재구축 건너뜀 — 다른 재구축이 진행 중 (사유 {})", reason);
                return Optional.empty();
            }
            BloomRebuildResult result;
            int hashCount;
            try (BloomRebuild rebuild = started.get()) {
                // 순서가 정확성의 근거다: 표시(begin) 뒤에 스냅숏을 읽는다.
                List<String> hashes = cachedHashSourcePort.findAllCachedHashes();
                hashCount = hashes.size();
                result = rebuild.complete(hashes);
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            if (result.swapped()) {
                metrics.rebuilt(reason, BloomFilterMetrics.SWAPPED);
                metrics.cleared(result.clearedBits());
                log.info("블룸 재구축 교체 — 사유 {}, 해시 {}건, {}ms, setBits {}, clearedBits {}",
                        reason, hashCount, elapsedMs, result.setBits(), result.clearedBits());
                refreshFill();
            } else {
                metrics.rebuilt(reason, BloomFilterMetrics.ABORTED);
                requests.request(reason);
                log.warn("블룸 재구축 교체 포기(pending 사라짐) — 사유 {}, 해시 {}건, {}ms, 다시 요청",
                        reason, hashCount, elapsedMs);
            }
            return Optional.of(result);
        } catch (RuntimeException e) {
            metrics.rebuilt(reason, BloomFilterMetrics.FAILED);
            requests.request(reason);
            log.error("블룸 재구축 실패 — 사유 {}, 다시 요청", reason, e);
            return Optional.empty();
        }
    }
}
