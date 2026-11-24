package com.teecherteamc.platform;

import com.trendmicro.tlsh.Tlsh;
import java.util.Random;

/** TLSH 테스트 픽스처. 실제 파일 없이 기준 TLSH에서 거리가 정확히 정해진 값을 합성한다. */
final class TlshFixtures {

    /** 거리 기준이 되는 TLSH. {@link #digestAt(int)}가 만드는 값은 모두 이 값과의 거리로 정해진다. */
    static final String REFERENCE = FingerprintImportRestIntegrationTest.TLSH;

    private TlshFixtures() {}

    /** {@link #REFERENCE}와의 거리가 정확히 distance인 TLSH. 같은 distance는 항상 같은 값을 돌려준다. */
    static String digestAt(int distance) {
        return digestAt(REFERENCE, distance);
    }

    /**
     * reference와의 거리(길이 항 포함)가 정확히 distance인 TLSH. 시드가 고정이라 결정적이다.
     * 정책 경계용 메타데이터만 합성하며, 실제 바이트 변종 검증은 padding distance17 테스트가 맡는다.
     */
    static String digestAt(String reference, int distance) {
        Tlsh parsed = Tlsh.fromTlshStr(reference);
        Random random = new Random(713);
        for (int i = 0; i < 100000; i++) {
            char[] candidate = reference.toCharArray();
            for (int j = 0, n = 1 + random.nextInt(30); j < n; j++) {
                candidate[8 + random.nextInt(64)] = "0123456789ABCDEF".charAt(random.nextInt(16));
            }
            String value = new String(candidate);
            if (parsed.totalDiff(Tlsh.fromTlshStr(value), true) == distance) {
                return value;
            }
        }
        throw new AssertionError("fixture not found: " + distance);
    }
}
