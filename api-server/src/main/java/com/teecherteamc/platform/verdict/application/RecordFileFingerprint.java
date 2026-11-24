package com.teecherteamc.platform.verdict.application;

import com.teecherteamc.platform.verdict.domain.FileFingerprintStore;
import com.teecherteamc.platform.verdict.domain.FingerprintCalculation;
import com.teecherteamc.platform.verdict.domain.TlshCalculationPort;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RecordFileFingerprint {
    private final TlshCalculationPort calculator;
    private final FileFingerprintStore store;
    // TLSH는 파일 전체를 읽는 CPU 작업이다. 업로드가 몰릴 때 전부 함께 계산하면 CPU를 나눠 써서 모두가 늦게
    // 끝나고(300건 동시 시 첫 판정 약 50초), 엔진 차례도 그만큼 밀린다. CPU 수만큼만 계산하고 나머지는 도착
    // 순서대로 기다리게 해서, 먼저 끝난 파일이 바로 엔진으로 넘어가게 한다.
    private final Semaphore permits;

    public RecordFileFingerprint(TlshCalculationPort calculator, FileFingerprintStore store,
            @Value("${verdict.fingerprint.max-concurrent}") int maxConcurrent) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("verdict.fingerprint.max-concurrent는 1 이상이어야 한다: " + maxConcurrent);
        }
        this.calculator = calculator;
        this.store = store;
        this.permits = new Semaphore(maxConcurrent, true);
    }

    public void recordVerified(String sha256, Path file) {
        // 파일 I/O는 트랜잭션 밖이다. DB 장애는 계산 불가로 삼키지 않고 호출자에게 전파한다.
        store.saveVerified(sha256, calculate(file));
    }

    private FingerprintCalculation calculate(Path file) {
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            // 종료 중 인터럽트다. 지문 없이 넘어가지 않고 요청을 실패시킨다(호출자가 거부한다).
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TLSH 계산 차례를 기다리다 인터럽트됐다", e);
        }
        try {
            return calculator.calculate(file);
        } finally {
            permits.release();
        }
    }
}
