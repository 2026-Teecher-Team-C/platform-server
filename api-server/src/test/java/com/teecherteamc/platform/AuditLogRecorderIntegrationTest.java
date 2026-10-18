package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.teecherteamc.platform.audit.application.AuditLogRecorder;
import com.teecherteamc.platform.audit.domain.AuditAction;
import com.teecherteamc.platform.audit.domain.AuditTargetType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

// 다른 REST 통합 테스트와 같은 설정을 써서 스프링 컨텍스트(컨테이너 포함)를 공유한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
class AuditLogRecorderIntegrationTest {

    @Autowired
    private AuditLogRecorder recorder;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID seedAdmin() {
        UUID adminId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO admin_users (admin_id, username, password_hash, role) VALUES (?, ?, 'x', 'ADMIN')",
                adminId, "audit-" + adminId);
        return adminId;
    }

    private List<Map<String, Object>> rowsFor(String targetId) {
        return jdbcTemplate.queryForList(
                """
                SELECT actor_id, action, target_type, target_id, detail, host(ip_address) AS ip
                FROM audit_logs WHERE target_id = ?
                """,
                targetId);
    }

    @Test
    void 호출한_쪽_트랜잭션이_커밋되면_감사_로그가_남는다() {
        UUID actorId = seedAdmin();
        String targetId = UUID.randomUUID().toString();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("after", Map.of("reason", "사내 배포 도구"));

        transactionTemplate.executeWithoutResult(status -> recorder.record(
                actorId, AuditAction.LOGIN, AuditTargetType.ADMIN_USER, targetId, detail, "203.0.113.7"));

        List<Map<String, Object>> rows = rowsFor(targetId);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("actor_id")).isEqualTo(actorId);
        assertThat(row.get("action")).isEqualTo("LOGIN");
        assertThat(row.get("target_type")).isEqualTo("ADMIN_USER");
        assertThat(row.get("detail")).isEqualTo("{\"after\":{\"reason\":\"사내 배포 도구\"}}");
        assertThat(row.get("ip")).isEqualTo("203.0.113.7");
    }

    @Test
    void 호출한_쪽_트랜잭션이_롤백되면_감사_로그도_남지_않는다() {
        String targetId = UUID.randomUUID().toString();

        transactionTemplate.executeWithoutResult(status -> {
            recorder.record(null, AuditAction.LOGIN_FAILED, AuditTargetType.ADMIN_USER, targetId,
                    Map.of(), "203.0.113.8");
            status.setRollbackOnly();
        });

        assertThat(rowsFor(targetId)).isEmpty();
    }

    @Test
    void 트랜잭션_밖에서_부르면_기록하지_않고_예외를_던진다() {
        String targetId = UUID.randomUUID().toString();

        assertThatThrownBy(() -> recorder.record(null, AuditAction.LOGIN_FAILED, AuditTargetType.ADMIN_USER,
                targetId, Map.of(), "203.0.113.9"))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(rowsFor(targetId)).isEmpty();
    }

    @Test
    void IPv6_주소도_INET으로_저장된다() {
        String targetId = UUID.randomUUID().toString();

        transactionTemplate.executeWithoutResult(status -> recorder.record(
                null, AuditAction.LOGIN_FAILED, AuditTargetType.ADMIN_USER, targetId, null, "2001:db8::1"));

        Map<String, Object> row = rowsFor(targetId).get(0);
        assertThat(row.get("ip")).isEqualTo("2001:db8::1");
        assertThat(row.get("detail")).isNull();
    }

    @Test
    void IP_형식이_아니면_IP_없이_기록한다() {
        // 호스트 이름을 넘기면 InetAddress가 DNS를 조회하게 된다. 리터럴이 아니면 버린다.
        String targetId = UUID.randomUUID().toString();

        transactionTemplate.executeWithoutResult(status -> recorder.record(
                null, AuditAction.LOGIN_FAILED, AuditTargetType.ADMIN_USER, targetId, Map.of(), "example.com"));

        Map<String, Object> row = rowsFor(targetId).get(0);
        assertThat(row.get("ip")).isNull();
    }
}
