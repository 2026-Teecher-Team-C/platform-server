package com.teecherteamc.platform.ruleset.application;

import com.teecherteamc.platform.audit.application.AuditLogRecorder;
import com.teecherteamc.platform.audit.domain.AuditAction;
import com.teecherteamc.platform.audit.domain.AuditTargetType;
import com.teecherteamc.platform.ruleset.domain.CompileResult;
import com.teecherteamc.platform.ruleset.domain.CompiledRule;
import com.teecherteamc.platform.ruleset.domain.EngineRulesetPort;
import com.teecherteamc.platform.ruleset.domain.Ruleset;
import com.teecherteamc.platform.ruleset.domain.RulesetRepository;
import com.teecherteamc.platform.ruleset.domain.YaraRule;
import com.teecherteamc.platform.ruleset.domain.YaraRuleRepository;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * .yar 룰셋 파일을 올려 새 대기 버전으로 저장한다. (POST /api/admin/rulesets)
 *
 * 파일 검사와 엔진 컴파일을 먼저 하고, DB 트랜잭션은 저장 직전에야 연다. 엔진을 기다리는 동안
 * DB 커넥션을 잡고 있지 않기 위해서다. 버전 번호는 트랜잭션 안에서 최대값+1로 정하고, 동시에 올려
 * 같은 번호가 겹치면 PK 충돌(DataIntegrityViolationException)로 드러난다. 409 변환은 컨트롤러가 한다.
 * 엔진에 닿지 못하면 EngineRulesetUnavailableException이 그대로 올라간다.
 */
@Service
public class UploadRulesetUseCase {

    static final int MAX_BYTES = 1024 * 1024;
    private static final Set<String> SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final String DEFAULT_SEVERITY = "MEDIUM";

    private final RulesetRepository rulesetRepository;
    private final YaraRuleRepository yaraRuleRepository;
    private final EngineRulesetPort engine;
    private final AuditLogRecorder auditLogRecorder;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public UploadRulesetUseCase(RulesetRepository rulesetRepository, YaraRuleRepository yaraRuleRepository,
                                EngineRulesetPort engine, AuditLogRecorder auditLogRecorder,
                                PlatformTransactionManager transactionManager, Clock clock) {
        this.rulesetRepository = rulesetRepository;
        this.yaraRuleRepository = yaraRuleRepository;
        this.engine = engine;
        this.auditLogRecorder = auditLogRecorder;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public record Command(String filename, byte[] content, UUID adminId, String ipAddress) {
    }

    public Ruleset upload(Command command) {
        String filename = command.filename() == null ? "" : command.filename();
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".yar")) {
            throw new InvalidRulesetException(InvalidRulesetException.Kind.BAD_INPUT, ".yar 파일만 올릴 수 있습니다.");
        }
        byte[] content = command.content();
        if (content == null || content.length == 0) {
            throw new InvalidRulesetException(InvalidRulesetException.Kind.BAD_INPUT, "빈 파일입니다.");
        }
        if (content.length > MAX_BYTES) {
            throw new InvalidRulesetException(InvalidRulesetException.Kind.BAD_INPUT, "파일은 1MiB 이하여야 합니다.");
        }
        String source = decodeUtf8(content);

        CompileResult result = engine.compile(source);
        if (!result.ok()) {
            String message = result.errorMessage() == null || result.errorMessage().isBlank()
                    ? "룰셋을 컴파일할 수 없습니다." : result.errorMessage();
            throw new InvalidRulesetException(InvalidRulesetException.Kind.UNPROCESSABLE, message);
        }
        List<CompiledRule> compiled = result.rules() == null ? List.of() : result.rules();
        if (compiled.isEmpty()) {
            throw new InvalidRulesetException(InvalidRulesetException.Kind.UNPROCESSABLE, "룰이 하나도 없습니다.");
        }

        return transactionTemplate.execute(status -> save(command, filename, source, compiled));
    }

    private Ruleset save(Command command, String filename, String source, List<CompiledRule> compiled) {
        int version = rulesetRepository.findMaxVersion() + 1;
        Ruleset ruleset = rulesetRepository.save(
                Ruleset.upload(version, source, compiled.size(), command.adminId(), clock.instant()));
        yaraRuleRepository.saveAll(compiled.stream()
                .map(rule -> YaraRule.of(version, rule.name(), normalizeSeverity(rule.severity())))
                .toList());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("filename", filename);
        detail.put("ruleCount", compiled.size());
        auditLogRecorder.record(command.adminId(), AuditAction.UPLOAD_RULESET, AuditTargetType.RULESET,
                String.valueOf(version), detail, command.ipAddress());
        return ruleset;
    }

    private static String normalizeSeverity(String severity) {
        String upper = severity == null ? "" : severity.toUpperCase(Locale.ROOT);
        return SEVERITIES.contains(upper) ? upper : DEFAULT_SEVERITY;
    }

    private static String decodeUtf8(byte[] content) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException e) {
            throw new InvalidRulesetException(InvalidRulesetException.Kind.BAD_INPUT, "UTF-8 텍스트 파일이어야 합니다.");
        }
    }
}
