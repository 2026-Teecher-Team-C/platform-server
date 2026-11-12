package com.teecherteamc.platform.verdict.application;

import com.teecherteamc.platform.verdict.domain.ActiveRulesetKeyPort;
import com.teecherteamc.platform.verdict.domain.Analysis;
import com.teecherteamc.platform.verdict.domain.AnalysisOutcome;
import com.teecherteamc.platform.verdict.domain.AnalysisRepository;
import com.teecherteamc.platform.verdict.domain.EngineAnalysisPort;
import com.teecherteamc.platform.verdict.domain.EngineMatch;
import com.teecherteamc.platform.verdict.domain.EngineUnavailableException;
import com.teecherteamc.platform.verdict.domain.FileVerdict;
import com.teecherteamc.platform.verdict.domain.FileVerdictRepository;
import com.teecherteamc.platform.verdict.domain.HashMismatchException;
import com.teecherteamc.platform.verdict.domain.PendingQuarantineUploadPort;
import com.teecherteamc.platform.verdict.domain.QuarantineUploadPort;
import com.teecherteamc.platform.verdict.domain.QuarantineUploadResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SubmitFileUseCase {

    private static final Logger log = LoggerFactory.getLogger(SubmitFileUseCase.class);
    private static final String SOURCE_ENGINE = "ENGINE";
    private static final String SOURCE_CACHE = "CACHE";
    // V2__seed_default_ruleset.sql이 심어둔 placeholder 행. 엔진 호출 자체가 실패해서 어떤 룰셋이었는지
    // 알 길이 없을 때, analyses.rulesets_version(NOT NULL)을 채우는 용도로 재사용한다.
    private static final int FALLBACK_RULESETS_VERSION = 0;

    private final FileVerdictRepository fileVerdictRepository;
    private final AnalysisRepository analysisRepository;
    private final EngineAnalysisPort engineAnalysisPort;
    private final QuarantineUploadPort quarantineUploadPort;
    private final PendingQuarantineUploadPort pendingQuarantineUploadPort;
    private final ActiveRulesetKeyPort activeRulesetKeyPort;
    private final TransactionTemplate transactionTemplate;
    private final RecordFileFingerprint recordFileFingerprint;
    private final SimilarityDecisions similarity;
    private final EventDecisionEvidence evidence;
    private final EventQuarantineUploads eventQuarantineUploads;

    public SubmitFileUseCase(
            FileVerdictRepository fileVerdictRepository,
            AnalysisRepository analysisRepository,
            EngineAnalysisPort engineAnalysisPort,
            QuarantineUploadPort quarantineUploadPort,
            PendingQuarantineUploadPort pendingQuarantineUploadPort,
            ActiveRulesetKeyPort activeRulesetKeyPort,
            PlatformTransactionManager transactionManager,
            RecordFileFingerprint recordFileFingerprint,
            SimilarityDecisions similarity, EventDecisionEvidence evidence, EventQuarantineUploads eventQuarantineUploads) {
        this.fileVerdictRepository = fileVerdictRepository;
        this.analysisRepository = analysisRepository;
        this.engineAnalysisPort = engineAnalysisPort;
        this.quarantineUploadPort = quarantineUploadPort;
        this.pendingQuarantineUploadPort = pendingQuarantineUploadPort;
        this.activeRulesetKeyPort = activeRulesetKeyPort;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.recordFileFingerprint = recordFileFingerprint;
        this.similarity = similarity;
        this.evidence = evidence;
        this.eventQuarantineUploads = eventQuarantineUploads;
    }

    /**
     * 전체를 한 트랜잭션으로 묶지 않는다 — 엔진 호출이 최대 65초 걸리는데, 그동안 DB 커넥션을 쥐고
     * 있으면 동시 업로드가 몰릴 때 커넥션 풀이 바닥난다. 캐시 조회·결과 저장만 각자 짧은 트랜잭션으로
     * 끊고, 엔진 호출은 트랜잭션 바깥에서 한다. 같은 빈 안에서 @Transactional 메서드를 또 호출하면
     * self-invocation이라 프록시를 안 거쳐서 트랜잭션이 안 걸리므로, TransactionTemplate으로 직접
     * 경계를 관리한다.
     */
    public SubmitFileResult submitFile(SubmitFileCommand command) {
        if (!command.verifiedSha256().equals(command.claimedSha256())) {
            // 해시 불일치를 알았으니, 에이전트가 주장한 값이 아니라 실제 내용을 기준으로 재검증한다.
            // 호출자(interfaces)가 이 예외를 잡아 DATA_LOSS로 거부하고 스풀을 지운다.
            throw new HashMismatchException(command.claimedSha256(), command.verifiedSha256());
        }

        transactionTemplate.executeWithoutResult(status -> evidence.claim(command.eventId(), command.verifiedSha256(), command.agentId()));

        // SHA 검증 뒤, 캐시 반환 전에 지문을 남긴다. 불일치 업로드는 이 경로에 도달하지 않는다.
        recordFileFingerprint.recordVerified(command.verifiedSha256(), command.spoolFile());

        boolean[] recomputedBlock = {false};
        SubmitFileResult cacheResult = transactionTemplate.execute(status -> {
            evidence.claim(command.eventId(), command.verifiedSha256(), command.agentId());
            similarity.lockCorpus();
            similarity.lockFile(command.verifiedSha256());
            SubmitFileResult exact = similarity.exactOverride(command.verifiedSha256());
            if (exact != null) {
                similarity.clearPending(command.eventId());
                return exact;
            }
            // 룰 변경의 stale UPDATE와 재평가 저장 사이에서 stale=false를 되살리는 경쟁을 막는다.
            activeRulesetKeyPort.lockActiveKey();
            Optional<FileVerdict> cached = fileVerdictRepository.findBySha256(command.verifiedSha256());
            if (cached.isPresent() && !cached.get().isStale()) {
                FileVerdict v = cached.get();
                boolean requiresEvidence = "CLEAN".equals(v.getVerdict()) || "SIMILARITY".equals(v.getVerdictSource());
                if (!requiresEvidence || similarity.cacheValid(command.verifiedSha256())) {
                    SubmitFileResult result = fromCachedVerdict(v);
                    if (result != null) similarity.bindCached(command.verifiedSha256(), command.eventId());
                    return result;
                }
                // corpus만 바뀐 경우 원본 YARA 성공은 여전히 유효하다. rules stale은 이 분기에 들어오지 않는다.
                if (requiresEvidence) {
                    Optional<UUID> cleanAnalysis = similarity.reusableCleanAnalysis(command.verifiedSha256());
                    if (cleanAnalysis.isPresent()) {
                        var assessment = similarity.compare(command.verifiedSha256());
                        boolean block = "BLOCK".equals(assessment.classification());
                        v.recordSimilarityPolicy(block);
                        fileVerdictRepository.save(v);
                        similarity.save(command.verifiedSha256(), command.eventId(), cleanAnalysis.get(), assessment);
                        recomputedBlock[0] = block;
                        return block ? new SubmitFileResult("BLOCK", "SIMILARITY", "TLSH distance: " + assessment.distance(), List.of())
                                : new SubmitFileResult("ALLOW", SOURCE_CACHE, null, List.of());
                    }
                }
            }
            return null;
        });
        if (cacheResult != null) {
            if (recomputedBlock[0]) quarantine(command);
            return cacheResult;
        }

        AnalysisOutcome outcome;
        try {
            outcome = engineAnalysisPort.analyze(command.spoolFile(), command.fileSize()); // 트랜잭션 밖
        } catch (EngineUnavailableException e) {
            // 엔진이 구조화된 응답조차 못 준 경우 — 이대로 두면 analyses에 아무 기록도 안 남는다.
            // 호출자(interfaces)가 UNAVAILABLE로 거부하도록 그대로 다시 던지되, 그 전에 감사 기록은
            // 남겨둔다(verdict=ERROR라 CheckHash/SubmitFile 둘 다 캐시 미스로 취급 — 다음 요청은
            // 다시 엔진을 부른다).
            recordEngineFailure(command, e);
            throw e;
        }

        SubmitFileResult result = persistOutcome(command, outcome);
        // 최종 정책 저장 뒤에 격리한다. 비교 중 목록이 바뀌어도 최종 BLOCK을 기준으로 하며,
        // 느린 S3 호출 중에는 corpus/DB 잠금을 잡지 않는다. 저장소 실패는 차단을 취소하지 않는다.
        if ("SIMILARITY".equals(result.source()) || ("ENGINE".equals(result.source()) && isFreshMaliciousOrSuspicious(outcome))) {
            quarantine(command);
        }
        return result;
    }

    /** 저장소 I/O는 DB/corpus 잠금 밖에서 실행하고 이벤트별 지연 완료를 조정한다. */
    private void quarantine(SubmitFileCommand command) {
            try {
                QuarantineUploadResult upload = quarantineUploadPort.upload(command.spoolFile());
                transactionTemplate.executeWithoutResult(status -> {
                    if (command.eventId() == null) {
                        pendingQuarantineUploadPort.save(command.verifiedSha256(), command.fileSize(), upload.bucket(), upload.key());
                    } else {
                        eventQuarantineUploads.complete(command.eventId(), command.verifiedSha256(), command.agentId(), command.fileSize(), upload);
                    }
                });
            } catch (RuntimeException e) {
                log.error("S3 격리 업로드 실패 — 격리 없이 BLOCK 유지, sha256={}", command.verifiedSha256(), e);
            }
    }

    private static boolean isFreshMaliciousOrSuspicious(AnalysisOutcome outcome) {
        return "SUCCESS".equals(outcome.status())
                && ("MALICIOUS".equals(outcome.verdict()) || "SUSPICIOUS".equals(outcome.verdict()));
    }

    private SubmitFileResult persistOutcome(
            SubmitFileCommand command, AnalysisOutcome outcome) {
        return transactionTemplate.execute(status -> {
            evidence.claim(command.eventId(), command.verifiedSha256(), command.agentId());
            similarity.lockCorpus();
            similarity.lockFile(command.verifiedSha256());
            Optional<FileVerdict> current = fileVerdictRepository.findBySha256(command.verifiedSha256());
            // 활성 룰셋에 공유 잠금을 건 채로 지금 활성 키를 읽는다(file_verdicts를 쓰기 전에 — 잠금 순서를
            // 활성화·룰 토글과 같게 둔다). 엔진이 판정에 쓴 키와 다르면, 분석 도중 활성화·토글이 커밋돼
            // 그쪽 stale UPDATE가 아직 없던 이 행을 놓친 것이다 — 옛 룰 판정이므로 stale로 저장해 다음 요청
            // 때 재검사되게 한다. 잠금 덕에 이 트랜잭션이 커밋되기 전에는 새 활성화·토글이 커밋되지 못하고,
            // 커밋된 뒤의 활성화·토글은 이 행을 보고 stale로 만든다.
            boolean staleRules = !activeRulesetKeyPort.lockActiveKey()
                    .map(activeKey -> activeKey.equals(outcome.rulesetKey()))
                    .orElse(false);
            // status가 SUCCESS가 아니면(TIMEOUT/CRASH/OOM/UNSUPPORTED) toResult()는 무조건 BLOCK을
            // 돌려주는데, 캐시에는 outcome.verdict()를 그대로 저장하면 안 된다 — status/verdict는
            // proto상 독립된 필드라 "실패했는데 verdict=CLEAN" 같은 조합이 이론상 가능하고, 그러면
            // 다음 CheckHash가 캐시만 보고 ALLOW를 내줘서 fail-close가 캐시 레벨에서 뚫린다.
            SubmitFileResult exact = similarity.exactOverride(command.verifiedSha256());
            com.teecherteamc.platform.verdict.domain.SimilarityDecisionStore.Assessment assessment = exact == null && "SUCCESS".equals(outcome.status()) && "CLEAN".equals(outcome.verdict())
                    ? similarity.compare(command.verifiedSha256()) : null;
            boolean similarityBlock = assessment != null && "BLOCK".equals(assessment.classification());
            String source = similarityBlock ? "SIMILARITY" : SOURCE_ENGINE;
            String cachedVerdict = similarityBlock ? "MALICIOUS" : "SUCCESS".equals(outcome.status()) ? outcome.verdict() : "ERROR";
            FileVerdict fileVerdict = current
                    .map(fv -> {
                        fv.recordAnalysis(
                                cachedVerdict, source, outcome.rulesetsVersion(),
                                outcome.detectedFileType(), Instant.now());
                        return fv;
                    })
                    .orElseGet(() -> FileVerdict.create(
                            command.verifiedSha256(),
                            command.fileSize(),
                            outcome.detectedFileType(),
                            cachedVerdict,
                            source,
                            outcome.rulesetsVersion(),
                            Instant.now()));
            // 검사 중 등록된 정확 예외가 해제된 뒤, 예전 엔진 BLOCK이 되살아나지 않게 한다.
            if (staleRules || (exact != null && "WHITELIST".equals(exact.source()))) {
                fileVerdict.markStale();
            }
            fileVerdictRepository.save(fileVerdict);
            UUID analysis = saveAnalysis(command.verifiedSha256(), outcome);
            if (assessment != null) similarity.save(command.verifiedSha256(), command.eventId(), analysis, assessment);
            else {
                similarity.clearCache(command.verifiedSha256());
                similarity.clearPending(command.eventId());
            }
            // 검사에서 이미 확인한 악성/의심 응답은 도중에 추가된 예외로 소급 해제하지 않는다.
            // stale 표시는 유지하여 이후 독립 요청의 정확 예외와 예외 해제 후 재검사를 보존한다.
            if (!"SUCCESS".equals(outcome.status()) || isFreshMaliciousOrSuspicious(outcome)) return toResult(outcome);
            if (exact != null) return exact;
            return similarityBlock
                    ? new SubmitFileResult("BLOCK", "SIMILARITY", "TLSH distance: " + assessment.distance(), List.of())
                    : toResult(outcome);
        });
    }

    private void recordEngineFailure(SubmitFileCommand command, EngineUnavailableException e) {
        String sha256 = command.verifiedSha256();
        long fileSize = command.fileSize();
        transactionTemplate.executeWithoutResult(status -> {
            evidence.claim(command.eventId(), sha256, command.agentId());
            similarity.lockCorpus();
            similarity.lockFile(sha256);
            // 캐시를 읽은 뒤 엔진을 기다리는 동안 다른 업로드가 저장할 수 있어 잠금 뒤 현재 행을 다시 읽는다.
            FileVerdict verdict = fileVerdictRepository.findBySha256(sha256).map(existing -> {
                existing.recordAnalysis("ERROR", SOURCE_ENGINE, null, null, Instant.now());
                return existing;
            }).orElseGet(() -> FileVerdict.create(
                    sha256, fileSize, null, "ERROR", SOURCE_ENGINE, null, Instant.now()));
            fileVerdictRepository.save(verdict);
            similarity.clearCache(sha256);
            similarity.clearPending(command.eventId());
            analysisRepository.save(Analysis.record(sha256, null, FALLBACK_RULESETS_VERSION, "unavailable",
                    "TIMEOUT", "ERROR", null, false, null, 0, e.getMessage()));
        });
    }

    private UUID saveAnalysis(String sha256, AnalysisOutcome outcome) {
        Analysis analysis = Analysis.record(
                sha256,
                // ReportEvent 이전이라 analyses.event_id FK는 아직 쓸 수 없다.
                // 대신 similarity_assessments.analysis_id와 event_decision_evidence를 통해 정확한 요청에 연결한다.
                null,
                outcome.rulesetsVersion(),
                outcome.engineVersion(),
                outcome.status(),
                outcome.verdict(),
                outcome.detectedFileType(),
                outcome.peParsed(),
                outcome.maxSectionEntropy() == null ? null : BigDecimal.valueOf(outcome.maxSectionEntropy()),
                outcome.durationMs(),
                outcome.errorMessage());
        for (EngineMatch match : outcome.matches()) {
            analysis.addMatch(match.ruleName(), match.severity(), toJson(match.matchedStrings()));
        }
        analysisRepository.save(analysis);
        return analysis.getAnalysisId();
    }

    private SubmitFileResult fromCachedVerdict(FileVerdict fileVerdict) {
        return switch (fileVerdict.getVerdict()) {
            case "CLEAN" -> new SubmitFileResult("ALLOW", SOURCE_CACHE, null, List.of());
            case "MALICIOUS", "SUSPICIOUS" -> new SubmitFileResult(
                    "BLOCK", "SIMILARITY".equals(fileVerdict.getVerdictSource()) ? "SIMILARITY" : SOURCE_CACHE, "cached verdict: " + fileVerdict.getVerdict(), List.of());
            default -> null;
        };
    }

    private SubmitFileResult toResult(AnalysisOutcome outcome) {
        List<String> ruleNames = outcome.matches().stream().map(EngineMatch::ruleName).toList();
        if (!"SUCCESS".equals(outcome.status())) {
            // 크래시·타임아웃·OOM도 BLOCK이다 — fail-close 원칙의 연장.
            return new SubmitFileResult("BLOCK", SOURCE_ENGINE, "엔진 분석 실패: " + outcome.status(), ruleNames);
        }
        return switch (outcome.verdict()) {
            case "CLEAN" -> new SubmitFileResult("ALLOW", SOURCE_ENGINE, null, ruleNames);
            case "MALICIOUS", "SUSPICIOUS" -> new SubmitFileResult(
                    "BLOCK", SOURCE_ENGINE, String.join(", ", ruleNames), ruleNames);
            // SubmitFile은 UNKNOWN을 돌려줄 수 없다(더 올려볼 데가 없다) — 안전한 쪽인 BLOCK으로 확정.
            default -> new SubmitFileResult(
                    "BLOCK", SOURCE_ENGINE, "엔진이 결론을 내지 못함: " + outcome.verdict(), ruleNames);
        };
    }

    /** 문자열 리스트를 JSON 배열로 직접 만든다 — 단순한 형태라 Jackson 의존성 없이 처리한다. */
    private static String toJson(List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(escapeJson(values.get(i))).append('"');
        }
        return json.append(']').toString();
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append("\\u%04x".formatted((int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
