package com.teecherteamc.platform.ruleset.application;

import com.teecherteamc.platform.ruleset.domain.RulesetDetectionChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 룰 구성 변경이 커밋된 뒤 엔진에 새 룰셋을 적재한다. 엔진이 내려가 있어도 변경 자체는 이미 커밋됐으므로
 * 예외는 삼키고 로그만 남긴다(엔진 키가 어긋나면 판정 쪽에서 드러나고 재적재로 복구한다).
 */
@Component
class ActiveRulesetPusher {

    private static final Logger log = LoggerFactory.getLogger(ActiveRulesetPusher.class);

    private final ActiveRulesetService activeRulesetService;

    ActiveRulesetPusher(ActiveRulesetService activeRulesetService) {
        this.activeRulesetService = activeRulesetService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(RulesetDetectionChanged event) {
        try {
            activeRulesetService.pushActive();
        } catch (Exception e) {
            log.warn("룰셋 v{} 엔진 적재에 실패했습니다. 변경은 저장됐습니다.", event.version(), e);
        }
    }
}
