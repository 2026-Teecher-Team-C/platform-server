package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

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
class BloomFilterRebuildMergeIntegrationTest {

    private static final byte[] KEY = "bloom:file_verdicts".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private BloomFilterPort bloomFilterPort;

    @Autowired
    private RedisConnectionFactory connectionFactory;

    @Value("${lookup.bloom-filter.expected-hash-count}")
    private long expectedHashCount;

    @Value("${lookup.bloom-filter.false-positive-rate}")
    private double falsePositiveRate;

    @Test
    void Redis가_비워진_뒤_즉시_추가된_해시도_재구축_뒤에_남는다() {
        String added = "4d".repeat(32);
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.keyCommands().del(KEY);
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
        long bitSize = (long) Math.ceil(-(expectedHashCount * Math.log(falsePositiveRate)) / (Math.log(2) * Math.log(2)));
        byte[] larger = new byte[(int) ((bitSize + 7) / 8) + 16];
        java.util.Arrays.fill(larger, (byte) 0xFF);
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.stringCommands().set(KEY, larger);
        }

        bloomFilterPort.rebuild(List.of("3c".repeat(32)));

        try (RedisConnection connection = connectionFactory.getConnection()) {
            assertThat(connection.stringCommands().strLen(KEY)).isEqualTo((bitSize + 7) / 8);
            byte[] head = connection.stringCommands().getRange(KEY, 0, 2);
            assertThat(head).as("옛 블룸의 0xFF 비트가 OR로 섞이면 안 된다").isNotEqualTo(new byte[] {-1, -1, -1});
        }
    }
}
