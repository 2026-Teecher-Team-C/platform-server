package com.teecherteamc.platform.ruleset.application;

import com.teecherteamc.platform.ruleset.domain.EngineRulesetPort;
import com.teecherteamc.platform.ruleset.domain.Ruleset;
import com.teecherteamc.platform.ruleset.domain.RulesetRepository;
import com.teecherteamc.platform.ruleset.domain.RulesetSnapshot;
import com.teecherteamc.platform.ruleset.domain.YaraRuleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 지금 활성인 룰셋을 엔진에 맞추는 일. 커밋 직후(AFTER_COMMIT)에도 부를 수 있도록 읽기를 새 트랜잭션에서 한다.
 */
@Service
public class ActiveRulesetService {

    private final RulesetRepository rulesetRepository;
    private final YaraRuleRepository yaraRuleRepository;
    private final EngineRulesetPort engine;
    private final TransactionTemplate readTx;

    public ActiveRulesetService(RulesetRepository rulesetRepository, YaraRuleRepository yaraRuleRepository,
                                EngineRulesetPort engine, PlatformTransactionManager transactionManager) {
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTx.setReadOnly(true);
        this.rulesetRepository = rulesetRepository;
        this.yaraRuleRepository = yaraRuleRepository;
        this.engine = engine;
    }

    /** 활성 버전의 원문과 꺼진 룰 이름. 활성 버전이 없으면 IllegalStateException. */
    public RulesetSnapshot activeSnapshot() {
        // 프록시를 거치지 않는 내부 호출에서도 항상 새 읽기 트랜잭션에서 읽는다
        return readTx.execute(status -> readActive());
    }

    private RulesetSnapshot readActive() {
        Ruleset active = rulesetRepository.findActive()
                .orElseThrow(() -> new IllegalStateException("활성 룰셋이 없습니다."));
        return new RulesetSnapshot(active.getVersion(), active.getSource(),
                yaraRuleRepository.findDisabledNames(active.getVersion()));
    }

    /** 활성 스냅샷을 새 트랜잭션에서 읽고, 트랜잭션 밖에서 엔진에 적재한다(gRPC 동안 DB 커넥션을 잡지 않는다). */
    public void pushActive() {
        engine.load(activeSnapshot());
    }
}
