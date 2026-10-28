package com.teecherteamc.platform.ruleset.application;

import com.teecherteamc.platform.audit.application.AuditLogRecorder;
import com.teecherteamc.platform.audit.domain.AuditAction;
import com.teecherteamc.platform.audit.domain.AuditTargetType;
import com.teecherteamc.platform.ruleset.domain.Ruleset;
import com.teecherteamc.platform.ruleset.domain.RulesetDetectionChanged;
import com.teecherteamc.platform.ruleset.domain.RulesetRepository;
import com.teecherteamc.platform.ruleset.domain.YaraRule;
import com.teecherteamc.platform.ruleset.domain.YaraRuleRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 룰 하나의 사용 여부를 바꾼다. (PATCH /api/admin/rulesets/{version}/rules/{ruleId})
 * 활성 버전의 룰일 때만 엔진 구성이 달라지므로 그때만 이벤트를 발행한다.
 */
@Service
public class ChangeYaraRuleEnabledUseCase {

    private final RulesetRepository rulesetRepository;
    private final YaraRuleRepository yaraRuleRepository;
    private final AuditLogRecorder auditLogRecorder;
    private final ApplicationEventPublisher events;

    public ChangeYaraRuleEnabledUseCase(RulesetRepository rulesetRepository, YaraRuleRepository yaraRuleRepository,
                                        AuditLogRecorder auditLogRecorder, ApplicationEventPublisher events) {
        this.rulesetRepository = rulesetRepository;
        this.yaraRuleRepository = yaraRuleRepository;
        this.auditLogRecorder = auditLogRecorder;
        this.events = events;
    }

    @Transactional
    public YaraRule change(int version, UUID ruleId, boolean enabled, UUID adminId, String ip) {
        Ruleset ruleset = version <= 0 ? null : rulesetRepository.findByVersion(version).orElse(null);
        if (ruleset == null) {
            throw RulesetNotFoundException.ofVersion(version);
        }
        YaraRule rule = yaraRuleRepository.findByVersionAndId(version, ruleId)
                .orElseThrow(() -> new RulesetNotFoundException("룰셋 v" + version + "에 해당 룰이 없습니다."));
        if (rule.isEnabled() == enabled) {
            return rule;
        }
        rule.changeEnabled(enabled);
        YaraRule saved = yaraRuleRepository.save(rule);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("version", version);
        detail.put("ruleName", saved.getRuleName());
        auditLogRecorder.record(adminId, enabled ? AuditAction.ENABLE_YARA_RULE : AuditAction.DISABLE_YARA_RULE,
                AuditTargetType.YARA_RULE, ruleId.toString(), detail, ip);
        if (ruleset.isActive()) {
            events.publishEvent(new RulesetDetectionChanged(version));
        }
        return saved;
    }
}
