package com.teecherteamc.platform.verdict.application;

import com.teecherteamc.platform.verdict.domain.FileFingerprintStore;
import com.teecherteamc.platform.verdict.domain.TlshCalculationPort;
import java.nio.file.Path;
import org.springframework.stereotype.Service;

@Service
public class RecordFileFingerprint {
    private final TlshCalculationPort calculator;
    private final FileFingerprintStore store;

    public RecordFileFingerprint(TlshCalculationPort calculator, FileFingerprintStore store) {
        this.calculator = calculator;
        this.store = store;
    }

    public void recordVerified(String sha256, Path file) {
        // 파일 I/O는 트랜잭션 밖이다. DB 장애는 계산 불가로 삼키지 않고 호출자에게 전파한다.
        var result = calculator.calculate(file);
        store.saveVerified(sha256, result);
    }
}
