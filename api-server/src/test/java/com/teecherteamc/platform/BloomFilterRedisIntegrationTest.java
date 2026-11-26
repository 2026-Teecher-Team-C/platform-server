package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.teecherteamc.platform.lookup.application.BloomRebuildRequests;
import com.teecherteamc.platform.lookup.domain.BloomFilterPort;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
 * 블룸 재구축은 새 비트맵을 live 블룸과 원자적으로 OR한 뒤 교체한다. 스냅샷 뒤 즉시 추가된 해시가 교체로 사라지지 않고,
 * 비트 수가 다른(설정이 바뀐) 옛 블룸은 섞지 않고 버린다.
 */
@SpringBootTest
@AutoConfigureTestGrpcTransport
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "lookup.bloom-filter.rebuild-interval-ms=86400000")
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
        bloomFilterPort.add("6f".repeat(32));

        assertThat(bloomFilterPort.mightContain("70".repeat(32))).isFalse();
    }

    @Test
    void 재구축_중이면_추가가_pending_키에도_쓰인다() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.stringCommands().set(pendingKey(), new byte[0]);
        }
        bloomFilterPort.add("81".repeat(32));
        try (RedisConnection connection = connectionFactory.getConnection()) {
            assertThat(connection.stringCommands().bitCount(pendingKey())).isEqualTo(hashCount());
            connection.keyCommands().del(pendingKey());
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
    void Redis가_비워진_뒤_즉시_추가된_해시도_재구축_뒤에_남는다() {
        String added = "4d".repeat(32);
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.keyCommands().del(key());
        }
        bloomFilterPort.add(added);

        bloomFilterPort.rebuild(List.of());

        assertThat(bloomFilterPort.mightContain(added)).isTrue();
    }

    @Test
    void 재구축_중_즉시_추가된_해시는_교체_뒤에도_남는다() {
        String addedDuringRebuild = "1a".repeat(32);
        String inSnapshot = "2b".repeat(32);
        bloomFilterPort.add(addedDuringRebuild);

        bloomFilterPort.rebuild(List.of(inSnapshot));

        assertThat(bloomFilterPort.mightContain(addedDuringRebuild)).isTrue();
        assertThat(bloomFilterPort.mightContain(inSnapshot)).isTrue();
    }

    @Test
    void 비트_수가_더_컸던_옛_블룸은_합치지_않고_교체한다() {
        long bitSize = bitSize();
        byte[] larger = new byte[(int) ((bitSize + 7) / 8) + 16];
        java.util.Arrays.fill(larger, (byte) 0xFF);
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.stringCommands().set(key(), larger);
        }

        bloomFilterPort.rebuild(List.of("3c".repeat(32)));

        try (RedisConnection connection = connectionFactory.getConnection()) {
            assertThat(connection.stringCommands().strLen(key())).isEqualTo((bitSize + 7) / 8);
            byte[] head = connection.stringCommands().getRange(key(), 0, 2);
            assertThat(head).as("옛 블룸의 0xFF 비트가 OR로 섞이면 안 된다").isNotEqualTo(new byte[] {-1, -1, -1});
        }
    }
}
