package com.teecherteamc.platform.ruleset.application;

import com.teecherteamc.platform.audit.application.AuditLogRecorder;
import com.teecherteamc.platform.audit.domain.AuditAction;
import com.teecherteamc.platform.audit.domain.AuditTargetType;
import com.teecherteamc.platform.ruleset.domain.Ruleset;
import com.teecherteamc.platform.ruleset.domain.RulesetDetectionChanged;
import com.teecherteamc.platform.ruleset.domain.RulesetRepository;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 룰셋 버전을 활성화한다. (POST /api/admin/rulesets/{version}/activate)
 * 이미 활성이면 아무것도 바꾸지 않는다. 바꾸면 이벤트로 엔진 판정을 stale 처리하고, 엔진 적재는 커밋 후에 한다.
 */
@Service
public class ActivateRulesetUseCase {

    private final RulesetRepository rulesetRepository;
    private final AuditLogRecorder auditLogRecorder;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ActivateRulesetUseCase(RulesetRepository rulesetRepository, AuditLogRecorder auditLogRecorder,
                                  ApplicationEventPublisher events, Clock clock) {
        this.rulesetRepository = rulesetRepository;
        this.auditLogRecorder = auditLogRecorder;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public Ruleset activate(int version, UUID adminId, String ip) {
        Ruleset target = version <= 0 ? null : rulesetRepository.findByVersion(version).orElse(null);
        if (target == null) {
            throw RulesetNotFoundException.ofVersion(version);
        }
        if (target.isActive()) {
            return target;
        }
        Integer previousVersion = rulesetRepository.findActive().map(Ruleset::getVersion).orElse(null);

        // deactivateAll은 영속성 컨텍스트를 비우므로 대상을 다시 읽는다
        rulesetRepository.deactivateAll();
        Ruleset reloaded = rulesetRepository.findByVersion(version)
                .orElseThrow(() -> RulesetNotFoundException.ofVersion(version));
        reloaded.activate(adminId, clock.instant());
        Ruleset saved = rulesetRepository.save(reloaded);

        events.publishEvent(new RulesetDetectionChanged(version));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("previousVersion", previousVersion);
        auditLogRecorder.record(adminId, AuditAction.ACTIVATE_RULESET, AuditTargetType.RULESET,
                String.valueOf(version), detail, ip);
        return saved;
    }
}
