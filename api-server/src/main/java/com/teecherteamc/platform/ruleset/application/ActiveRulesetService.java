package com.teecherteamc.platform.ruleset.application;

import com.teecherteamc.platform.ruleset.domain.EngineRulesetPort;
import com.teecherteamc.platform.ruleset.domain.Ruleset;
import com.teecherteamc.platform.ruleset.domain.RulesetRepository;
import com.teecherteamc.platform.ruleset.domain.RulesetSnapshot;
import com.teecherteamc.platform.ruleset.domain.YaraRuleRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
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

    /**
     * 호출자 트랜잭션 안에서 활성 룰셋 행에 공유 잠금을 걸고 지금 활성 키(RulesetSnapshot.key()와 같은 값)를 준다.
     * 잠금은 호출자 트랜잭션이 끝날 때까지 유지되어, 그 사이 활성화·활성 버전 룰 토글이 커밋되지 못한다.
     * 활성화가 막 커밋돼 활성 행을 못 찾으면 비어 있다 — 호출자는 키가 바뀐 것으로 다뤄야 한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<String> lockActiveKey() {
        return rulesetRepository.lockActiveVersionForShare()
                .map(version -> RulesetSnapshot.keyOf(version, yaraRuleRepository.findDisabledNames(version)));
    }

    /** 활성 스냅샷을 새 트랜잭션에서 읽고, 트랜잭션 밖에서 엔진에 적재한다(gRPC 동안 DB 커넥션을 잡지 않는다). */
    public void pushActive() {
        engine.load(activeSnapshot());
    }
}
