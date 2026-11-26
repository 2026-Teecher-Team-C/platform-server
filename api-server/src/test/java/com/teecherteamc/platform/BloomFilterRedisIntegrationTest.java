package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.teecherteamc.platform.lookup.application.BloomRebuildRequests;
import com.teecherteamc.platform.lookup.domain.BloomFillStats;
import com.teecherteamc.platform.lookup.domain.BloomFilterPort;
import com.teecherteamc.platform.lookup.domain.BloomRebuildResult;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.TestPropertySource;

/**
 * Redis 블룸의 실제 동작을 확인한다: 키 이름(비트 수 포함), 키 없음 감지와 재구축 요청, 재구축 중 그 재구축의 pending
 * 비트맵({@code <live>:pending:<token>})으로의 이중 쓰기, 그리고 OR 없는 깨끗한 재구축 — 원천에서 빠진 해시의 비트는
 * 지워지고(지운 비트 수를 센다), 표시 키를 세운 뒤 추가된 해시는 pending 비트맵을 통해 교체 뒤에도 남는다. 표시
 * ({@code <live>:pending})가 사라졌거나 다른 재구축의 토큰으로 바뀌었으면 교체하지 않고, 남의 표시는 지우지 않는다.
 * 채움 통계도 여기서 본다.
 */
@SpringBootTest
@AutoConfigureTestGrpcTransport
@Import(TestcontainersConfiguration.class)
// 요청 폴링·채움 확인을 사실상 끈다 — 키를 지운 테스트가 남긴 재구축 요청을 백그라운드가 가져가 live를 되살리면 결과가 흔들린다.
@TestPropertySource(properties = {
        "lookup.bloom-filter.rebuild-interval-ms=86400000",
        "lookup.bloom-filter.request-poll-ms=86400000",
        "lookup.bloom-filter.fill-check-ms=86400000"})
class BloomFilterRedisIntegrationTest {

    @Autowired
    private BloomFilterPort bloomFilterPort;

    @Autowired
    private BloomRebuildRequests requests;

    @Autowired
    private RedisConnectionFactory connectionFactory;

    @Value("${lookup.bloom-filter.expected-hash-count}")
    private long expectedHashCount;

    @Value("${lookup.bloom-filter.false-positive-rate}")
    private double falsePositiveRate;

    private String liveKey;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void clearPending() throws InterruptedException {
        BloomTestSupport.awaitStartupRebuild(meterRegistry);
        liveKey = bloomFilterPort.keyName();
        del(liveKey + ":pending");
    }

    private byte[] key() {
        return bloomFilterPort.keyName().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] pendingKey() {
        return (bloomFilterPort.keyName() + ":pending").getBytes(StandardCharsets.UTF_8);
    }

    private long bitSize() {
        return (long) Math.ceil(-(expectedHashCount * Math.log(falsePositiveRate)) / (Math.log(2) * Math.log(2)));
    }

    private int hashCount() {
        return Math.max(1, (int) Math.round((double) bitSize() / expectedHashCount * Math.log(2)));
    }

    @Test
    void Redis_run_id를_읽는다() {
        assertThat(bloomFilterPort.serverRunId()).isNotBlank();
    }

    @Test
    void 키_이름에_비트_수가_들어간다() {
        assertThat(bloomFilterPort.keyName()).isEqualTo("bloom:file_verdicts:m" + bitSize());
    }

    @Test
    void 키가_없으면_있을_수_있음으로_답하고_재구축을_요청한다() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.keyCommands().del(key());
        }
        requests.take();

        assertThat(bloomFilterPort.mightContain("5e".repeat(32))).isTrue();
        assertThat(requests.take()).contains("missing");
    }

    @Test
    void 키가_있으면_없는_해시는_없음이다() {
        rebuildWith(List.of("6f".repeat(32)));

        assertThat(bloomFilterPort.mightContain("70".repeat(32))).isFalse();
    }

    @Test
    void live가_없을_때_추가해도_live를_만들지_않고_재구축을_요청한다() {
        // 추가가 live를 새로 만들면 그 해시만 든 부분 키가 생겨, 다른 목록 해시가 재구축 전까지 "없음"으로 나온다(거짓 음성).
        del(liveKey);
        requests.take();

        bloomFilterPort.add("a7".repeat(32));

        assertThat(exists(liveKey)).isFalse();
        assertThat(requests.take()).contains("missing");
        assertThat(bloomFilterPort.mightContain("b8".repeat(32))).isTrue();
    }

    @Test
    void live가_없을_때_일괄_추가해도_live를_만들지_않고_재구축을_요청한다() {
        del(liveKey);
        requests.take();

        bloomFilterPort.addAll(List.of("a8".repeat(32), "a9".repeat(32)));

        assertThat(exists(liveKey)).isFalse();
        assertThat(requests.take()).contains("missing");
    }

    @Test
    void live가_없을_때_재구축_중에_추가한_해시도_교체_뒤에_남는다() {
        del(liveKey);
        String during = "c9".repeat(32);
        try (var b = bloomFilterPort.beginRebuild().orElseThrow()) {
            bloomFilterPort.add(during);
            assertThat(b.complete(List.of("d9".repeat(32))).swapped()).isTrue();
        }

        assertThat(bloomFilterPort.mightContain(during)).isTrue();
        assertThat(bloomFilterPort.mightContain("d9".repeat(32))).isTrue();
    }

    @Test
    void 재구축_중이면_추가가_그_재구축의_pending_비트맵에도_쓰인다() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.stringCommands().set(pendingKey(), bytes("t1"));
        }
        try {
            bloomFilterPort.add("81".repeat(32));
            try (RedisConnection connection = connectionFactory.getConnection()) {
                assertThat(connection.stringCommands().bitCount(bytes(liveKey + ":pending:t1"))).isEqualTo(hashCount());
            }
        } finally {
            del(liveKey + ":pending");
            del(liveKey + ":pending:t1");
        }
    }

    @Test
    void 재구축_중이_아니면_pending_키를_만들지_않는다() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.keyCommands().del(pendingKey());
        }
        bloomFilterPort.addAll(List.of("92".repeat(32)));
        try (RedisConnection connection = connectionFactory.getConnection()) {
            assertThat(connection.keyCommands().exists(pendingKey())).isFalse();
        }
    }

    @Test
    void 원천에서_빠진_해시는_재구축_뒤_사라지고_지운_비트를_센다() {
        String old = "a1".repeat(32);
        rebuildWith(List.of(old));

        BloomRebuildResult r = rebuildWith(List.of("b2".repeat(32)));

        assertThat(r.swapped()).isTrue();
        assertThat(bloomFilterPort.mightContain(old)).isFalse();
        assertThat(r.clearedBits()).isPositive();
    }

    @Test
    void 표시_키를_세운_뒤_추가된_해시는_교체_뒤에도_남는다() {
        String during = "c3".repeat(32);
        try (var b = bloomFilterPort.beginRebuild().orElseThrow()) {
            bloomFilterPort.add(during); // 스냅숏 이후 커밋·추가 흉내
            b.complete(List.of("d4".repeat(32)));
        }

        assertThat(bloomFilterPort.mightContain(during)).isTrue();
    }

    @Test
    void 재구축_중이면_두_번째_재구축은_시작하지_않는다() {
        try (var first = bloomFilterPort.beginRebuild().orElseThrow()) {
            assertThat(bloomFilterPort.beginRebuild()).isEmpty();
        }
    }

    @Test
    void pending이_사라지면_교체하지_않고_live를_그대로_둔다() {
        rebuildWith(List.of("e5".repeat(32)));
        try (var b = bloomFilterPort.beginRebuild().orElseThrow()) {
            del(liveKey + ":pending"); // TTL 만료 흉내
            assertThat(b.complete(List.of()).swapped()).isFalse();
        }

        assertThat(bloomFilterPort.mightContain("e5".repeat(32))).isTrue();
    }

    @Test
    void complete_없이_닫으면_표시와_자기_pending_비트맵을_지운다() {
        var rebuild = bloomFilterPort.beginRebuild().orElseThrow();
        String token = marker();
        assertThat(exists(liveKey + ":pending:" + token)).isTrue();

        rebuild.close();

        assertThat(exists(liveKey + ":pending")).isFalse();
        assertThat(exists(liveKey + ":pending:" + token)).isFalse();
    }

    @Test
    void 표시가_다른_재구축의_것으로_바뀌었으면_교체하지_않고_그_재구축을_건드리지_않는다() {
        rebuildWith(List.of("e6".repeat(32)));
        try (var a = bloomFilterPort.beginRebuild().orElseThrow()) {
            del(liveKey + ":pending"); // A의 표시가 TTL로 만료된 것을 흉내
            try (var b = bloomFilterPort.beginRebuild().orElseThrow()) {
                String tokenB = marker();

                assertThat(a.complete(List.of()).swapped()).isFalse();

                assertThat(marker()).isEqualTo(tokenB);
                assertThat(exists(liveKey + ":pending:" + tokenB)).isTrue();
                assertThat(bloomFilterPort.mightContain("e6".repeat(32))).isTrue();
            }
        }
    }

    @Test
    void 닫을_때_다른_재구축의_표시는_지우지_않는다() {
        var a = bloomFilterPort.beginRebuild().orElseThrow();
        del(liveKey + ":pending"); // A의 표시가 TTL로 만료된 것을 흉내
        try (var b = bloomFilterPort.beginRebuild().orElseThrow()) {
            String tokenB = marker();

            a.close();

            assertThat(marker()).isEqualTo(tokenB);
            assertThat(exists(liveKey + ":pending:" + tokenB)).isTrue();
        }
    }

    @Test
    void 키가_없을_때_재구축하면_새로_만든다() {
        del(liveKey);

        BloomRebuildResult r = rebuildWith(List.of("f6".repeat(32)));

        assertThat(r.clearedBits()).isZero();
        assertThat(bloomFilterPort.mightContain("f6".repeat(32))).isTrue();
    }

    @Test
    void 채움_통계는_비트_수와_추정_오탐률을_준다() {
        rebuildWith(List.of("07".repeat(32)));

        BloomFillStats s = bloomFilterPort.fillStats().orElseThrow();

        assertThat(s.setBits()).isEqualTo(hashCount());
        assertThat(s.bitSize()).isEqualTo(bitSize());
        assertThat(s.estimatedFpp()).isEqualTo(Math.pow((double) hashCount() / bitSize(), hashCount()));
    }

    @Test
    void live가_없으면_채움_통계는_비어_있다() {
        del(liveKey);

        assertThat(bloomFilterPort.fillStats()).isEmpty();
    }

    private BloomRebuildResult rebuildWith(List<String> hashes) {
        try (var b = bloomFilterPort.beginRebuild().orElseThrow()) {
            return b.complete(hashes);
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private String marker() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            byte[] value = connection.stringCommands().get(bytes(liveKey + ":pending"));
            return value == null ? null : new String(value, StandardCharsets.UTF_8);
        }
    }

    private void del(String name) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.keyCommands().del(name.getBytes(StandardCharsets.UTF_8));
        }
    }

    private boolean exists(String name) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            return Boolean.TRUE.equals(connection.keyCommands().exists(name.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
