package com.teecherteamc.platform.dashboard.application;

import com.teecherteamc.platform.dashboard.domain.EngineStatus;
import com.teecherteamc.platform.dashboard.domain.EngineStatus.EngineState;
import com.teecherteamc.platform.dashboard.domain.EngineStatusPort;
import com.teecherteamc.platform.dashboard.domain.EngineStatusPort.EngineActivity;
import com.teecherteamc.platform.dashboard.domain.EngineStatusPort.FailCloseImpact;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;

/** 탐지 엔진 상태 배지. 최근 10분 analyses 기준이고, DOWN일 때만 장애 시작 시각과 영향을 더 읽는다. */
@Service
public class EngineStatusUseCase {

    public static final Duration WINDOW = Duration.ofMinutes(10);

    private final EngineStatusPort port;

    public EngineStatusUseCase(EngineStatusPort port) {
        this.port = port;
    }

    public EngineStatus status(Instant now) {
        EngineActivity activity = port.latestActivity(now);
        EngineState state = EngineStatus.judge(activity.lastSuccessAt(), activity.lastFailureAt(), now, WINDOW);
        if (state != EngineState.DOWN) {
            return EngineStatus.notDown(state, activity.lastSuccessAt(), activity.lastFailureAt(), WINDOW);
        }
        // DOWN이면 마지막 SUCCESS 뒤에 실패(lastFailureAt)가 적어도 하나 있으므로 since는 null이 아니다.
        Instant since = port.firstFailureAfter(activity.lastSuccessAt(), now);
        FailCloseImpact impact = port.failClosesBetween(since, now);
        return EngineStatus.down(since, activity.lastSuccessAt(), activity.lastFailureAt(), WINDOW,
                impact.failCloses(), impact.affectedAgents());
    }
}
