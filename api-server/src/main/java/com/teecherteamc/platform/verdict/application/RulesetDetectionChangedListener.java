package com.teecherteamc.platform.verdict.application;

import com.teecherteamc.platform.ruleset.domain.RulesetDetectionChanged;
import com.teecherteamc.platform.verdict.domain.FileVerdictRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 엔진 룰 구성이 바뀌면 엔진이 내린 판정(ENGINE)을 모두 stale로 표시해 다음 요청 때 재검사되게 한다.
 * 화이트리스트·블랙리스트·수동 판정은 룰과 무관하므로 건드리지 않는다.
 * 발행한 쪽 트랜잭션 안에서 처리하므로 활성화·감사 로그와 함께 커밋되거나 취소된다.
 */
@Component
class RulesetDetectionChangedListener {

    private final FileVerdictRepository fileVerdicts;

    RulesetDetectionChangedListener(FileVerdictRepository fileVerdicts) {
        this.fileVerdicts = fileVerdicts;
    }

    @EventListener
    void on(RulesetDetectionChanged event) {
        fileVerdicts.markEngineVerdictsStale();
    }
}
