package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.teecherteamc.platform.dashboard.application.EngineStatusUseCase;
import com.teecherteamc.platform.dashboard.domain.EngineStatus;
import com.teecherteamc.platform.dashboard.domain.EngineStatus.EngineState;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 탐지 엔진 상태(최근 10분 analyses 기준). analyses는 테스트끼리 공유하므로 "지금"을 다른 테스트와 겹치지 않는
 * 과거 시각(seed 기간)으로 잡아 유스케이스를 직접 부른다. 계산 기준은 콘솔 mock(computeEngineStatus)과 같다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class EngineStatusIntegrationTest {

    @Autowired
    private EngineStatusUseCase useCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private DashboardSeed seed;
    private Instant now;

    @BeforeEach
    void setUp() {
        seed = new DashboardSeed(jdbcTemplate);
        now = seed.at(12);
    }

    private Instant minutesAgo(long minutes) {
        return now.minus(minutes, ChronoUnit.MINUTES);
    }

    @Test
    void 최근_기록이_SUCCESS면_HEALTHY이고_DOWN_전용_값은_비운다() {
        seed.analysisAt("CRASH", minutesAgo(5));
        seed.analysisAt("SUCCESS", minutesAgo(2));

        EngineStatus status = useCase.status(now);

        assertThat(status.state()).isEqualTo(EngineState.HEALTHY);
        assertThat(status.lastSuccessAt()).isEqualTo(minutesAgo(2));
        assertThat(status.lastFailureAt()).isEqualTo(minutesAgo(5));
        assertThat(status.since()).isNull();
        assertThat(status.failCloseSinceDown()).isZero();
        assertThat(status.affectedAgentsSinceDown()).isZero();
        assertThat(status.windowMinutes()).isEqualTo(10);
    }

    @Test
    void 마지막_실패_뒤_SUCCESS가_없으면_DOWN이고_since는_마지막_SUCCESS_이후_첫_실패다() {
        UUID agentA = seed.agent("PC-A");
        UUID agentB = seed.agent("PC-B");
        seed.analysisAt("TIMEOUT", minutesAgo(30));
        seed.analysisAt("SUCCESS", minutesAgo(20));
        seed.analysisAt("TIMEOUT", minutesAgo(8));
        seed.analysisAt("OOM", minutesAgo(3));
        // 장애 시작 전 FAIL_CLOSE와 now 뒤 FAIL_CLOSE는 세지 않는다.
        seed.failClose(agentA, "a.com", minutesAgo(9));
        seed.failClose(agentA, "a.com", minutesAgo(7));
        seed.failClose(agentA, "a.com", minutesAgo(6));
        seed.failClose(agentB, "b.com", minutesAgo(1));
        seed.failClose(agentB, "b.com", now.plus(1, ChronoUnit.MINUTES));

        EngineStatus status = useCase.status(now);

        assertThat(status.state()).isEqualTo(EngineState.DOWN);
        assertThat(status.since()).isEqualTo(minutesAgo(8));
        assertThat(status.lastSuccessAt()).isEqualTo(minutesAgo(20));
        assertThat(status.lastFailureAt()).isEqualTo(minutesAgo(3));
        assertThat(status.failCloseSinceDown()).isEqualTo(3);
        assertThat(status.affectedAgentsSinceDown()).isEqualTo(2);
    }

    @Test
    void UNSUPPORTED는_판단에서_뺀다() {
        // since가 다른 테스트가 남긴 과거 실패까지 거슬러 가지 않도록 앞에 SUCCESS를 둔다.
        seed.analysisAt("SUCCESS", minutesAgo(30));
        seed.analysisAt("CRASH", minutesAgo(4));
        seed.analysisAt("UNSUPPORTED", minutesAgo(1));

        EngineStatus status = useCase.status(now);

        assertThat(status.state()).isEqualTo(EngineState.DOWN);
        assertThat(status.since()).isEqualTo(minutesAgo(4));
    }

    @Test
    void 최근_10분_안에_기록이_없으면_IDLE이다() {
        seed.analysisAt("CRASH", minutesAgo(11));
        seed.analysisAt("UNSUPPORTED", minutesAgo(1));

        EngineStatus status = useCase.status(now);

        assertThat(status.state()).isEqualTo(EngineState.IDLE);
        assertThat(status.since()).isNull();
        assertThat(status.failCloseSinceDown()).isZero();
    }

    @Test
    void now_뒤의_기록은_보지_않는다() {
        seed.analysisAt("SUCCESS", minutesAgo(3));
        seed.analysisAt("CRASH", now.plus(1, ChronoUnit.MINUTES));

        assertThat(useCase.status(now).state()).isEqualTo(EngineState.HEALTHY);
    }
}
