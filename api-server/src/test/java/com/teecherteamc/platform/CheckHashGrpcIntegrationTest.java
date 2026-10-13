package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.teecherteamc.platform.lookup.application.BloomFilterRebuildService;
import com.teecherteamc.platform.proto.verdict.v1.CheckHashRequest;
import com.teecherteamc.platform.proto.verdict.v1.CheckHashResponse;
import com.teecherteamc.platform.proto.verdict.v1.Decision;
import com.teecherteamc.platform.proto.verdict.v1.DecisionSource;
import com.teecherteamc.platform.proto.verdict.v1.VerdictServiceGrpc;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.grpc.client.ImportGrpcClients;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@AutoConfigureTestGrpcTransport
@ImportGrpcClients(types = VerdictServiceGrpc.VerdictServiceBlockingStub.class)
@Import(TestcontainersConfiguration.class)
// 기동 시 1차 재구축(initialDelay=0)은 그대로 돈다. 주기만 테스트 실행 시간보다 훨씬 길게 둬서,
// 테스트 중간에 스케줄러가 다시 돌면서 방금 INSERT한 fixture를 멋대로 반영해버리는 비결정성을
// 없앤다 — 블룸 재구축은 각 테스트가 BloomFilterRebuildService.rebuild()로 직접 트리거한다.
@TestPropertySource(properties = "lookup.bloom-filter.rebuild-interval-ms=86400000")
class CheckHashGrpcIntegrationTest {

    @Autowired
    private VerdictServiceGrpc.VerdictServiceBlockingStub verdictStub;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BloomFilterRebuildService bloomFilterRebuildService;

    @Test
    void 블랙리스트에_있으면_BLOCK을_반환한다() {
        String sha256 = "a".repeat(64);
        jdbcTemplate.update(
                "INSERT INTO hash_blacklist (hash_type, hash_value, reason) VALUES ('SHA256', ?, 'EICAR test string')",
                sha256);

        CheckHashResponse response = verdictStub.checkHash(
                CheckHashRequest.newBuilder().setSha256(sha256).build());

        assertThat(response.getDecision()).isEqualTo(Decision.DECISION_BLOCK);
        assertThat(response.getSource()).isEqualTo(DecisionSource.DECISION_SOURCE_BLACKLIST);
        assertThat(response.getReason()).isEqualTo("EICAR test string");
    }

    @Test
    void 비활성화된_블랙리스트_항목은_무시하고_UNKNOWN을_반환한다() {
        String sha256 = "b".repeat(64);
        jdbcTemplate.update(
                """
                INSERT INTO hash_blacklist (hash_type, hash_value, reason, is_active, deactivated_at)
                VALUES ('SHA256', ?, 'revoked', false, now())
                """,
                sha256);

        CheckHashResponse response = verdictStub.checkHash(
                CheckHashRequest.newBuilder().setSha256(sha256).build());

        assertThat(response.getDecision()).isEqualTo(Decision.DECISION_UNKNOWN);
    }

    @Test
    void file_verdicts에_CLEAN으로_캐시돼_있으면_ALLOW를_반환한다() {
        String sha256 = "c".repeat(64);
        jdbcTemplate.update(
                "INSERT INTO file_verdicts (sha256, verdict, verdict_source) VALUES (?, 'CLEAN', 'MANUAL')", sha256);
        bloomFilterRebuildService.rebuild();

        CheckHashResponse response = verdictStub.checkHash(
                CheckHashRequest.newBuilder().setSha256(sha256).build());

        assertThat(response.getDecision()).isEqualTo(Decision.DECISION_ALLOW);
        assertThat(response.getSource()).isEqualTo(DecisionSource.DECISION_SOURCE_CACHE);
    }

    @Test
    void file_verdicts에_MALICIOUS로_캐시돼_있으면_BLOCK을_반환한다() {
        String sha256 = "d".repeat(64);
        jdbcTemplate.update(
                "INSERT INTO file_verdicts (sha256, verdict, verdict_source) VALUES (?, 'MALICIOUS', 'ENGINE')",
                sha256);
        bloomFilterRebuildService.rebuild();

        CheckHashResponse response = verdictStub.checkHash(
                CheckHashRequest.newBuilder().setSha256(sha256).build());

        assertThat(response.getDecision()).isEqualTo(Decision.DECISION_BLOCK);
        assertThat(response.getSource()).isEqualTo(DecisionSource.DECISION_SOURCE_CACHE);
    }

    @Test
    void is_stale인_캐시는_무시하고_UNKNOWN을_반환한다() {
        String sha256 = "e".repeat(64);
        jdbcTemplate.update(
                """
                INSERT INTO file_verdicts (sha256, verdict, verdict_source, is_stale)
                VALUES (?, 'CLEAN', 'MANUAL', true)
                """,
                sha256);
        bloomFilterRebuildService.rebuild();

        CheckHashResponse response = verdictStub.checkHash(
                CheckHashRequest.newBuilder().setSha256(sha256).build());

        assertThat(response.getDecision()).isEqualTo(Decision.DECISION_UNKNOWN);
    }

    @Test
    void 캐시가_있어도_블룸을_재구축하기_전이면_UNKNOWN을_반환한다() {
        String sha256 = "9".repeat(64);
        jdbcTemplate.update(
                "INSERT INTO file_verdicts (sha256, verdict, verdict_source) VALUES (?, 'CLEAN', 'MANUAL')", sha256);
        // bloomFilterRebuildService.rebuild() 호출 안 함 — 블룸이 이 해시를 아직 모른다.

        CheckHashResponse response = verdictStub.checkHash(
                CheckHashRequest.newBuilder().setSha256(sha256).build());

        assertThat(response.getDecision()).isEqualTo(Decision.DECISION_UNKNOWN);
    }

    @Test
    void 블랙리스트도_캐시도_없으면_UNKNOWN을_반환한다() {
        String sha256 = "f".repeat(64);

        CheckHashResponse response = verdictStub.checkHash(
                CheckHashRequest.newBuilder().setSha256(sha256).build());

        assertThat(response.getDecision()).isEqualTo(Decision.DECISION_UNKNOWN);
        assertThat(response.getSource()).isEqualTo(DecisionSource.DECISION_SOURCE_UNSPECIFIED);
    }

    @Test
    void 대문자_sha256도_정규화돼서_블랙리스트와_매치된다() {
        String sha256Lower = "1".repeat(64);
        jdbcTemplate.update(
                "INSERT INTO hash_blacklist (hash_type, hash_value, reason) VALUES ('SHA256', ?, 'normalized match')",
                sha256Lower);

        CheckHashResponse response = verdictStub.checkHash(
                CheckHashRequest.newBuilder().setSha256(sha256Lower.toUpperCase()).build());

        assertThat(response.getDecision()).isEqualTo(Decision.DECISION_BLOCK);
    }

    @Test
    void sha256_형식이_아니면_INVALID_ARGUMENT로_거부된다() {
        assertThatThrownBy(() -> verdictStub.checkHash(
                        CheckHashRequest.newBuilder().setSha256("not-a-hash").build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
    }
}
