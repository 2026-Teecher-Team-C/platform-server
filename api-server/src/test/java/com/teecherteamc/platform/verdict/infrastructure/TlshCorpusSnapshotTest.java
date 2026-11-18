package com.teecherteamc.platform.verdict.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.trendmicro.tlsh.Tlsh;
import com.trendmicro.tlsh.TlshCreator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** 스냅샷 최근접 = 라이브러리로 L ±5 창 안을 전부 비교한 결과(동률은 작은 SHA). indexed DB 경로와 같은 정의다. */
class TlshCorpusSnapshotTest {

    @Test
    void 최근접은_창_안_전체_비교와_같다() {
        Random random = new Random(20261119);
        List<byte[]> data = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            byte[] bytes = i > 0 && random.nextInt(3) == 0 ? mutate(data.get(random.nextInt(data.size())), random)
                    : randomBytes(random);
            data.add(bytes);
            rows.add(new String[] {sha(random), tlsh(bytes)});
        }
        // 같은 지문을 다른 SHA로 한 번 더 넣어 동률 규칙도 검사한다
        for (int i = 0; i < 50; i++) {
            rows.add(new String[] {sha(random), rows.get(random.nextInt(rows.size()))[1]});
        }
        TlshCorpusSnapshot.Builder builder = TlshCorpusSnapshot.builder("token-1");
        rows.forEach(r -> builder.add(r[0], r[1]));
        TlshCorpusSnapshot snapshot = builder.build();
        assertThat(snapshot.token()).isEqualTo("token-1");
        assertThat(snapshot.size()).isEqualTo(rows.size());

        for (int i = 0; i < 500; i++) {
            String input = tlsh(i % 2 == 0 ? mutate(data.get(random.nextInt(data.size())), random) : randomBytes(random));
            String[] expected = windowedBruteForce(rows, input);
            var actual = snapshot.nearest(input);
            if (expected == null) {
                assertThat(actual).as(input).isNull();
            } else {
                assertThat(actual).as(input).isNotNull();
                assertThat(actual.sha()).as(input).isEqualTo(expected[0]);
                assertThat(actual.tlsh()).as(input).isEqualTo(expected[1]);
                assertThat(actual.distance()).as(input).isEqualTo(Integer.parseInt(expected[2]));
            }
        }
    }

    @Test
    void 빈_corpus는_결과가_없다() {
        assertThat(TlshCorpusSnapshot.builder("empty").build().nearest(tlsh(randomBytes(new Random(1))))).isNull();
    }

    private static String[] windowedBruteForce(List<String[]> rows, String input) {
        List<Integer> window = Arrays.asList(TlshLValue.window(TlshLValue.of(input)));
        Tlsh in = Tlsh.fromTlshStr(input);
        String[] best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String[] row : rows) {
            if (!window.contains(TlshLValue.of(row[1]))) continue;
            int d = in.totalDiff(Tlsh.fromTlshStr(row[1]), true);
            if (d < bestDistance || (d == bestDistance && row[0].compareTo(best[0]) < 0)) {
                best = row;
                bestDistance = d;
            }
        }
        return best == null ? null : new String[] {best[0], best[1], String.valueOf(bestDistance)};
    }

    private static byte[] randomBytes(Random random) {
        byte[] bytes = new byte[100 + (int) Math.pow(2, 6 + random.nextDouble() * 11)];
        random.nextBytes(bytes);
        return bytes;
    }

    private static byte[] mutate(byte[] original, Random random) {
        byte[] copy = original.clone();
        for (int i = 0, n = 1 + random.nextInt(copy.length / 40 + 1); i < n; i++) {
            copy[random.nextInt(copy.length)] = (byte) random.nextInt(256);
        }
        return copy;
    }

    private static String tlsh(byte[] data) {
        TlshCreator creator = new TlshCreator();
        creator.update(data);
        return creator.getHash(true).getEncoded();
    }

    private static String sha(Random random) {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
