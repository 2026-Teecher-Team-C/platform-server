package com.teecherteamc.platform.verdict.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.trendmicro.tlsh.Tlsh;
import com.trendmicro.tlsh.TlshCreator;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** 메모리 corpus의 바이트 거리는 라이브러리 Tlsh.totalDiff(…, true)와 한 점도 다르면 안 된다. */
class TlshBytesTest {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    @Test
    void 실제_지문_쌍의_거리가_라이브러리와_같다() {
        Random random = new Random(20261119);
        List<String> hashes = new ArrayList<>();
        while (hashes.size() < 200) {
            byte[] data = new byte[64 + random.nextInt(200_000)];
            random.nextBytes(data);
            hashes.add(tlsh(data));
            // 가까운 변종도 섞는다(거리 0~수십): 몇 바이트만 바꾼다
            for (int k = 0; k < 1 + random.nextInt(data.length / 50 + 1); k++) {
                data[random.nextInt(data.length)] = (byte) random.nextInt(256);
            }
            hashes.add(tlsh(data));
        }
        for (int i = 0; i < 2000; i++) {
            assertSame(hashes.get(random.nextInt(hashes.size())), hashes.get(random.nextInt(hashes.size())));
        }
    }

    @Test
    void 무작위_hex_지문_쌍의_거리가_라이브러리와_같다() {
        Random random = new Random(7);
        for (int i = 0; i < 2000; i++) {
            String a = randomTlsh(random);
            String b = random.nextInt(4) == 0 ? mutate(a, random) : randomTlsh(random);
            assertSame(a, b);
        }
    }

    @Test
    void 바이트_표현은_문자열로_그대로_돌아오고_L_value가_같다() {
        Random random = new Random(11);
        for (int i = 0; i < 1000; i++) {
            String t = randomTlsh(random);
            byte[] bytes = TlshBytes.decode(t);
            assertThat(bytes).hasSize(TlshBytes.LENGTH);
            assertThat(TlshBytes.encode(bytes, 0)).isEqualTo(t);
            assertThat(TlshBytes.lvalue(bytes, 0)).isEqualTo(TlshLValue.of(t));
        }
    }

    private static void assertSame(String a, String b) {
        int expected = Tlsh.fromTlshStr(a).totalDiff(Tlsh.fromTlshStr(b), true);
        assertThat(TlshBytes.distance(TlshBytes.decode(a), 0, TlshBytes.decode(b), 0)).as(a + " / " + b).isEqualTo(expected);
    }

    private static String tlsh(byte[] data) {
        TlshCreator creator = new TlshCreator();
        creator.update(data);
        return creator.getHash(true).getEncoded();
    }

    private static String randomTlsh(Random random) {
        StringBuilder s = new StringBuilder("T1");
        for (int i = 0; i < 70; i++) {
            s.append(HEX[random.nextInt(16)]);
        }
        return s.toString();
    }

    private static String mutate(String t, Random random) {
        char[] c = t.toCharArray();
        for (int i = 0, n = 1 + random.nextInt(6); i < n; i++) {
            c[2 + random.nextInt(70)] = HEX[random.nextInt(16)];
        }
        return new String(c);
    }
}
