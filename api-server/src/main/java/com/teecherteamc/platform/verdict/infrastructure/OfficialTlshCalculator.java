package com.teecherteamc.platform.verdict.infrastructure;

import com.teecherteamc.platform.verdict.domain.FingerprintCalculation;
import com.teecherteamc.platform.verdict.domain.TlshCalculationPort;
import com.trendmicro.tlsh.TlshCreator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class OfficialTlshCalculator implements TlshCalculationPort {
    private static final Logger log = LoggerFactory.getLogger(OfficialTlshCalculator.class);

    @Override
    public FingerprintCalculation calculate(Path file) {
        // 요청마다 새 계산기를 사용한다. 전체 파일을 힙에 올리지 않고 고정 크기 버퍼로 읽는다.
        var creator = new TlshCreator();
        long length = 0;
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                creator.update(buffer, 0, read);
                length += read;
            }
            if (length < TlshCreator.MIN_FORCE_DATA_LENGTH) {
                return new FingerprintCalculation(null, "TOO_SHORT");
            }
            // Java 포트의 기본 최소값은 256이다. force=true로 공식 50바이트 모드를 명시한다.
            if (!creator.isValid(true)) {
                return new FingerprintCalculation(null, "LOW_ENTROPY");
            }
            return new FingerprintCalculation(creator.getHash(true).getEncoded(), "CALCULATED");
        } catch (IOException | RuntimeException e) {
            // 계산 실패만 보조 신호 부재로 처리한다. DB, 엔진 장애는 이 경계 밖에서 그대로 전파한다.
            log.warn("TLSH 산출 불가, 기존 검사는 계속함: {}", e.toString());
            return new FingerprintCalculation(null, "UNAVAILABLE");
        }
    }
}
