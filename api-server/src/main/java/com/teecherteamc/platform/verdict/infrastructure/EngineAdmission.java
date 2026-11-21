package com.teecherteamc.platform.verdict.infrastructure;

import com.teecherteamc.platform.verdict.domain.EngineUnavailableException;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 탐지 엔진에 동시에 넣는 Analyze 호출 수를 엔진 worker 수 안으로 묶는다. 엔진이 받지 못하는 호출까지 한꺼번에
 * 열면 엔진 앞에서 줄을 서는 동안 각 호출의 deadline이 흐르고, 보낸 청크가 api-server 메모리에 쌓인다.
 * 차례를 기다리는 업로드는 디스크 스풀에만 남는다. maxWait 안에 차례가 오지 않으면 엔진 실패로 끝낸다 —
 * 호출자가 UNAVAILABLE로 돌려주고 에이전트는 fail-close로 차단한다(재시도·폴백 아님).
 */
@Component
class EngineAdmission {

    private final Semaphore permits;
    private final Duration maxWait;

    EngineAdmission(@Value("${verdict.engine.max-concurrent}") int maxConcurrent,
            @Value("${verdict.engine.max-queue-wait}") Duration maxWait) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("verdict.engine.max-concurrent는 1 이상이어야 한다: " + maxConcurrent);
        }
        this.permits = new Semaphore(maxConcurrent, true);
        this.maxWait = maxWait;
    }

    <T> T run(Supplier<T> call) {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(maxWait.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EngineUnavailableException("엔진 차례를 기다리다 인터럽트됐다", e);
        }
        if (!acquired) {
            throw new EngineUnavailableException("엔진 대기 한도(" + maxWait.toSeconds() + "초) 초과", null);
        }
        try {
            return call.get();
        } finally {
            permits.release();
        }
    }
}
