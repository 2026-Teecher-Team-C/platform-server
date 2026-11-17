package com.teecherteamc.platform.verdict.infrastructure;

import com.trendmicro.tlsh.Tlsh;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * TLSH corpus 전체 비교에서 DB를 뺀 계산 비용을 잰다. 고도화 전 기준선이자, 이후 indexed 방식과 비교할 기준이다.
 *
 * <p>{@link JdbcSimilarityDecisionStore#compareTlsh}의 후보 루프를 그대로 옮겼다(거리 최소, 동률이면 SHA가 작은 쪽).
 * 운영 corpus(약 117만 건)는 {@link ParsedSimilarityCorpus}의 캐시 예산(8,192건)을 넘으므로 요청마다 모든 행을
 * 다시 파싱한다. 그래서 두 경로를 따로 잰다.
 * <ul>
 *   <li>{@code parseAndCompare}: 행마다 파싱 + 거리 계산. 예산을 넘는 corpus의 실제 경로</li>
 *   <li>{@code compareOnly}: 파싱된 후보로 거리 계산만. 예산 이내라 캐시에 걸린 경로</li>
 * </ul>
 * 운영 CheckHash 시간에서 이 값을 빼면 DB 커서로 corpus를 읽어 오는 비용이 남는다.
 *
 * <p>후보 TLSH는 시드를 고정한 무작위 문자열이다. 거리 계산은 값과 상관없이 같은 횟수의 연산을 하므로
 * 실제 지문 대신 써도 비용은 같다.
 *
 * <p>실행: 레포 루트에서 {@code make bench-tlsh} (특정 규모만: {@code make bench-tlsh ARGS="-p corpusSize=1000"})
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = {"-Xms3g", "-Xmx3g"})
public class TlshCorpusScanBenchmark {

    /** 1천, 캐시 예산 경계(8,192), 1만, 10만, 운영 규모(2026-11-16 기준 1,173,728건). */
    @Param({"1000", "8192", "10000", "100000", "1173728"})
    public int corpusSize;

    private static final long SEED = 20261117L;
    /** T1 + 체크섬 1바이트 + L 1바이트 + Q 1바이트 + 버킷 128개(32바이트) = 72자. 운영 지문과 같은 형식이다. */
    private static final int TLSH_HEX_BYTES = 35;

    private record Row(String sha, String encoded) {}
    private record Parsed(String sha, Tlsh parsed) {}

    private List<Row> rows;
    private List<Parsed> parsed;
    private Tlsh input;

    @Setup(Level.Trial)
    public void setUp() {
        SplittableRandom random = new SplittableRandom(SEED);
        rows = new ArrayList<>(corpusSize);
        parsed = new ArrayList<>(corpusSize);
        for (int i = 0; i < corpusSize; i++) {
            Row row = new Row(randomHex(random, 32), "T1" + randomHex(random, TLSH_HEX_BYTES));
            rows.add(row);
            parsed.add(new Parsed(row.sha(), Tlsh.fromTlshStr(row.encoded())));
        }
        input = Tlsh.fromTlshStr("T1" + randomHex(random, TLSH_HEX_BYTES));
    }

    @Benchmark
    public int parseAndCompare() {
        int bestDistance = Integer.MAX_VALUE;
        String bestSha = null;
        for (Row row : rows) {
            int distance = input.totalDiff(Tlsh.fromTlshStr(row.encoded()), true);
            if (distance < bestDistance || (distance == bestDistance && row.sha().compareTo(bestSha) < 0)) {
                bestDistance = distance;
                bestSha = row.sha();
            }
        }
        return bestDistance;
    }

    @Benchmark
    public int compareOnly() {
        int bestDistance = Integer.MAX_VALUE;
        String bestSha = null;
        for (Parsed candidate : parsed) {
            int distance = input.totalDiff(candidate.parsed(), true);
            if (distance < bestDistance || (distance == bestDistance && candidate.sha().compareTo(bestSha) < 0)) {
                bestDistance = distance;
                bestSha = candidate.sha();
            }
        }
        return bestDistance;
    }

    private static String randomHex(SplittableRandom random, int bytes) {
        byte[] buffer = new byte[bytes];
        random.nextBytes(buffer);
        return HexFormat.of().formatHex(buffer).toUpperCase();
    }
}
