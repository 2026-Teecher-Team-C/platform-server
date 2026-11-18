package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.teecherteamc.platform.hashlist.application.AddBlacklistEntryUseCase;
import com.teecherteamc.platform.proto.verdict.v1.CheckHashRequest;
import com.teecherteamc.platform.proto.verdict.v1.VerdictServiceGrpc;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.grpc.client.ImportGrpcClients;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * CheckHash 지연 지표(고도화 테스트 1-1). 조기 반환 경로도 전체 타이머에 decision/source 태그로 남고,
 * 단계 타이머와 p95 계산용 SLO 버킷(100ms·1s)이 붙는지 본다.
 */
@SpringBootTest
@AutoConfigureTestGrpcTransport
@ImportGrpcClients(types = VerdictServiceGrpc.VerdictServiceBlockingStub.class)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "lookup.bloom-filter.rebuild-interval-ms=86400000")
class CheckHashMetricsIntegrationTest {

    @Autowired
    private VerdictServiceGrpc.VerdictServiceBlockingStub verdictStub;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private AddBlacklistEntryUseCase addBlacklistEntryUseCase;

    @BeforeEach
    void authenticateVerdictClient() {
        verdictStub = VerdictTestClient.authenticated(verdictStub, jdbcTemplate);
    }

    @Test
    void 블랙리스트_차단은_전체_타이머에_BLOCK_BLACKLIST로_남고_목록_단계가_기록된다() {
        String sha256 = "d4".repeat(32);
        addBlacklistEntryUseCase.add(new AddBlacklistEntryUseCase.Command(sha256, "known bad", seedAdmin(), null));

        verdictStub.checkHash(CheckHashRequest.newBuilder().setSha256(sha256).build());

        assertThat(total("BLOCK", "BLACKLIST").count()).isGreaterThanOrEqualTo(1);
        assertThat(registry.find("verdict.checkhash.stage").tag("stage", "bloom").timer()).isNotNull();
        assertThat(registry.find("verdict.checkhash.stage").tag("stage", "lists").timer()).isNotNull();
    }

    @Test
    void 모르는_해시는_전체_타이머에_UNKNOWN_NONE으로_남는다() {
        verdictStub.checkHash(CheckHashRequest.newBuilder().setSha256("e5".repeat(32)).build());

        assertThat(total("UNKNOWN", "NONE").count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void 전체_타이머에_100ms와_1s_SLO_버킷이_있다() {
        verdictStub.checkHash(CheckHashRequest.newBuilder().setSha256("f6".repeat(32)).build());

        CountAtBucket[] buckets = total("UNKNOWN", "NONE").takeSnapshot().histogramCounts();
        assertThat(Arrays.stream(buckets).map(b -> (long) b.bucket(java.util.concurrent.TimeUnit.MILLISECONDS)))
                .contains(Duration.ofMillis(100).toMillis(), Duration.ofSeconds(1).toMillis());
    }

    private Timer total(String decision, String source) {
        Timer timer = registry.find("verdict.checkhash").tags("decision", decision, "source", source).timer();
        assertThat(timer).as("verdict.checkhash{decision=%s,source=%s}", decision, source).isNotNull();
        return timer;
    }

    private UUID seedAdmin() {
        UUID adminId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO admin_users (admin_id, username, password_hash) VALUES (?, ?, 'unused')",
                adminId, "metrics-seed-" + adminId);
        return adminId;
    }
}
