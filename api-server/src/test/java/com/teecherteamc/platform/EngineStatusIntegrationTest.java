package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.teecherteamc.platform.dashboard.application.DashboardStatusUseCase;
import com.teecherteamc.platform.dashboard.domain.EngineStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 탐지 엔진 상태(GET /api/admin/dashboard/engine-status). 이슈 #79. 계산 기준은 콘솔 mock(computeEngineStatus)과 같다.
 *
 * <p>상태는 DB 전체의 분석 기록으로 정해지므로, 판단 규칙은 다른 테스트의 기록이 없는 과거 시각을 "지금"으로 넘겨 확인한다.
 */
@SuppressWarnings("unchecked")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfiguration.class, AdminApiClient.class})
class EngineStatusIntegrationTest {

    @Autowired
    private DashboardStatusUseCase useCase;

    @Autowired
    private AdminApiClient adminApi;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private DashboardSeed seed;
    private Instant now;

    @BeforeEach
    void setUp() {
        seed = new DashboardSeed(jdbcTemplate);
        // 1900년대 아무 시각. 다른 테스트의 분석·이벤트는 모두 이보다 늦다.
        now = seed.windowStart().minus(100L * 365, ChronoUnit.DAYS);
    }

    private Instant minutesAgo(long minutes) {
        return now.minus(minutes, ChronoUnit.MINUTES);
    }

    @Test
    void 최근_10분에_기록이_없으면_IDLE이고_마지막_성공_실패_시각은_준다() {
        seed.analysisAt("SUCCESS", minutesAgo(30));
        seed.analysisAt("TIMEOUT", minutesAgo(20));

        EngineStatus status = useCase.engineStatus(now);

        assertThat(status.state()).isEqualTo("IDLE");
        assertThat(status.lastSuccessAt()).isEqualTo(minutesAgo(30));
        assertThat(status.lastFailureAt()).isEqualTo(minutesAgo(20));
        assertThat(status.since()).isNull();
        assertThat(status.windowMinutes()).isEqualTo(10);
    }

    @Test
    void 최근_기록이_SUCCESS면_HEALTHY다() {
        seed.analysisAt("CRASH", minutesAgo(5));
        seed.analysisAt("SUCCESS", minutesAgo(3));
        // UNSUPPORTED는 판단에서 뺀다.
        seed.analysisAt("UNSUPPORTED", minutesAgo(1));

        assertThat(useCase.engineStatus(now).state()).isEqualTo("HEALTHY");
    }

    @Test
    void 마지막_성공_뒤_실패만_있으면_DOWN이고_첫_실패부터_FAIL_CLOSE를_센다() {
        seed.analysisAt("SUCCESS", minutesAgo(30));
        seed.analysisAt("OOM", minutesAgo(15));
        seed.analysisAt("TIMEOUT", minutesAgo(2));
        UUID a = seed.agent("ENG-A");
        UUID b = seed.agent("ENG-B");
        seed.failClose(a, "a.com", minutesAgo(20));   // 다운 전
        seed.failClose(a, "a.com", minutesAgo(10));
        seed.failClose(a, "a.com", minutesAgo(5));
        seed.failClose(b, "b.com", minutesAgo(1));
        seed.failClose(b, "b.com", now.plus(1, ChronoUnit.MINUTES));   // 미래

        EngineStatus status = useCase.engineStatus(now);

        assertThat(status.state()).isEqualTo("DOWN");
        assertThat(status.since()).isEqualTo(minutesAgo(15));
        assertThat(status.lastSuccessAt()).isEqualTo(minutesAgo(30));
        assertThat(status.lastFailureAt()).isEqualTo(minutesAgo(2));
        assertThat(status.failCloseSinceDown()).isEqualTo(3);
        assertThat(status.affectedAgentsSinceDown()).isEqualTo(2);
    }

    @Test
    void 성공_기록이_한_번도_없으면_가장_이른_실패부터_다운이다() {
        // "그 전에 아무 기록도 없음"이 필요해, 이 클래스의 다른 테스트(1900년대)보다도 이른 1800년대를 쓴다.
        now = now.minus(50L * 365, ChronoUnit.DAYS);
        seed.analysisAt("CRASH", minutesAgo(40));
        seed.analysisAt("CRASH", minutesAgo(4));

        EngineStatus status = useCase.engineStatus(now);

        assertThat(status.state()).isEqualTo("DOWN");
        assertThat(status.since()).isEqualTo(minutesAgo(40));
        assertThat(status.lastSuccessAt()).isNull();
    }

    @Test
    void API는_콘솔_EngineStatus_모양으로_준다() {
        ResponseEntity<Map<String, Object>> response = adminApi.getForEntity(
                "/api/admin/dashboard/engine-status", (Class<Map<String, Object>>) (Class<?>) Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKeys("state", "since", "lastSuccessAt", "lastFailureAt",
                "windowMinutes", "failCloseSinceDown", "affectedAgentsSinceDown");
        assertThat(response.getBody().get("state")).isIn("HEALTHY", "DOWN", "IDLE");
    }
}
