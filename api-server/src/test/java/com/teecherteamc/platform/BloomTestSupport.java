package com.teecherteamc.platform;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;

/** 블룸 통합 테스트 공용 도우미. */
final class BloomTestSupport {

    private BloomTestSupport() {
    }

    /**
     * 기동 시 1차 재구축(scheduledRebuild, @Scheduled initialDelay=0)은 스케줄러 스레드에서 돈다. 그것이 표시(pending)를 쥔
     * 동안 테스트가 재구축을 부르면 건너뛰어지고, 테스트가 키를 지운 직후 끝나면 live를 다시 만들어 결과가 흔들리므로, 그
     * 실행이 끝났다는 스케줄 관측 지표를 기다린다.
     */
    static void awaitStartupRebuild(MeterRegistry meterRegistry) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            Timer timer = meterRegistry.find("tasks.scheduled.execution")
                    .tag("code.function", "scheduledRebuild")
                    .timer();
            if (timer != null && timer.count() > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("기동 시 블룸 재구축이 30초 안에 끝나지 않았다");
    }
}
