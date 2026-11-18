package com.teecherteamc.platform.verdict.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.trendmicro.tlsh.Tlsh;
import com.trendmicro.tlsh.TlshCreator;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * L-value 창(±5)으로 후보를 걸러도 거리 ≤70 후보를 놓치지 않는다는 근거. TLSH totalDiff의 길이 페널티는
 * ldiff(원형 mod 256)가 2 이상이면 ldiff*12라, ldiff ≥6이면 길이 항만으로 72다.
 */
class TlshLValueTest {

    @Test
    void 원형_L_차이가_5를_넘으면_거리는_72_이상이다() {
        Random random = new Random(7);
        List<String> hashes = new ArrayList<>();
        while (hashes.size() < 400) {
            byte[] data = new byte[64 + random.nextInt(200_000)];
            random.nextBytes(data);
            TlshCreator creator = new TlshCreator();
            creator.update(data);
            hashes.add(creator.getHash(true).getEncoded());
        }
        int checkedFar = 0;
        for (int i = 0; i < hashes.size(); i++) {
            for (int j = i + 1; j < hashes.size(); j += 7) {
                String a = hashes.get(i);
                String b = hashes.get(j);
                int ldiff = Math.floorMod(TlshLValue.of(a) - TlshLValue.of(b), 256);
                ldiff = Math.min(ldiff, 256 - ldiff);
                if (ldiff > TlshLValue.WINDOW) {
                    checkedFar++;
                    assertThat(Tlsh.fromTlshStr(a).totalDiff(Tlsh.fromTlshStr(b), true)).isGreaterThanOrEqualTo(72);
                }
            }
        }
        assertThat(checkedFar).as("창 밖 쌍을 충분히 검사했다").isGreaterThan(1000);
    }

    @Test
    void L_value는_네번째_다섯번째_문자를_니블_교환해_읽는다() {
        // "T1" + checksum(2) + L(2, 니블 교환) + Q(2) + body(64)
        assertThat(TlshLValue.of("T1AB" + "F0" + "0".repeat(66))).isEqualTo(0x0F);
        assertThat(TlshLValue.of("T1AB" + "1E" + "0".repeat(66))).isEqualTo(0xE1);
    }

    @Test
    void 창은_원형이라_0_근처에서_255쪽으로_넘어간다() {
        assertThat(TlshLValue.window(0)).containsExactlyInAnyOrder(251, 252, 253, 254, 255, 0, 1, 2, 3, 4, 5);
        assertThat(TlshLValue.window(253)).contains(255, 0, 1, 2, 248);
    }
}
