package com.teecherteamc.platform.verdict.application;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
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
    private final TransactionTemplate transactionTemplate;

    public SubmitFileUseCase(
            FileVerdictRepository fileVerdictRepository,
            AnalysisRepository analysisRepository,
            EngineAnalysisPort engineAnalysisPort,
            QuarantineUploadPort quarantineUploadPort,
            PendingQuarantineUploadPort pendingQuarantineUploadPort,
            PlatformTransactionManager transactionManager) {
        this.fileVerdictRepository = fileVerdictRepository;
        this.analysisRepository = analysisRepository;
        this.engineAnalysisPort = engineAnalysisPort;
        this.quarantineUploadPort = quarantineUploadPort;
        this.pendingQuarantineUploadPort = pendingQuarantineUploadPort;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
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

        Optional<FileVerdict> cached =
                transactionTemplate.execute(status -> fileVerdictRepository.findBySha256(command.verifiedSha256()));
        if (cached.isPresent() && !cached.get().isStale()) {
            SubmitFileResult fromCache = fromCachedVerdict(cached.get());
            if (fromCache != null) {
                return fromCache;
            }
            // CLEAN/MALICIOUS/SUSPICIOUS가 아닌(= 과거 UNKNOWN/ERROR) 캐시는 미스로 취급하고
            // 그대로 엔진을 호출한다.
        }

        AnalysisOutcome outcome;
        try {
            outcome = engineAnalysisPort.analyze(command.spoolFile(), command.fileSize()); // 트랜잭션 밖
        } catch (EngineUnavailableException e) {
            // 엔진이 구조화된 응답조차 못 준 경우 — 이대로 두면 analyses에 아무 기록도 안 남는다.
            // 호출자(interfaces)가 UNAVAILABLE로 거부하도록 그대로 다시 던지되, 그 전에 감사 기록은
            // 남겨둔다(verdict=ERROR라 CheckHash/SubmitFile 둘 다 캐시 미스로 취급 — 다음 요청은
            // 다시 엔진을 부른다).
            recordEngineFailure(command.verifiedSha256(), command.fileSize(), cached, e);
            throw e;
        }

        // 신규 엔진 호출에서만 격리 대상을 판단한다 — 캐시 히트는 fromCachedVerdict()에서 이미
        // 반환돼 여기 안 온다. S3 PUT도 엔진 호출처럼 느릴 수 있어 트랜잭션 밖에서 한다.
        QuarantineUploadResult quarantineUpload = null;
        if (isFreshMaliciousOrSuspicious(outcome)) {
            try {
                quarantineUpload = quarantineUploadPort.upload(command.spoolFile());
            } catch (RuntimeException e) {
                // S3 장애 하나가 엔진이 이미 확정한 MALICIOUS/SUSPICIOUS 판정 자체를 날려버리면
                // 안 된다 — 격리는 포기하고 판정 저장과 BLOCK 응답은 그대로 진행한다(fail-close
                // 원칙의 연장: 격리 저장소가 죽어도 판정 자체는 안전하게 내려가야 한다).
                log.error("S3 격리 업로드 실패 — 격리 없이 판정만 저장함, sha256={}", command.verifiedSha256(), e);
            }
        }

        return persistOutcome(command, cached, outcome, quarantineUpload);
    }

    private static boolean isFreshMaliciousOrSuspicious(AnalysisOutcome outcome) {
        return "SUCCESS".equals(outcome.status())
                && ("MALICIOUS".equals(outcome.verdict()) || "SUSPICIOUS".equals(outcome.verdict()));
    }

    private SubmitFileResult persistOutcome(
            SubmitFileCommand command, Optional<FileVerdict> cached, AnalysisOutcome outcome,
            QuarantineUploadResult quarantineUpload) {
        try {
            return transactionTemplate.execute(status -> {
                // status가 SUCCESS가 아니면(TIMEOUT/CRASH/OOM/UNSUPPORTED) toResult()는 무조건 BLOCK을
                // 돌려주는데, 캐시에는 outcome.verdict()를 그대로 저장하면 안 된다 — status/verdict는
                // proto상 독립된 필드라 "실패했는데 verdict=CLEAN" 같은 조합이 이론상 가능하고, 그러면
                // 다음 CheckHash가 캐시만 보고 ALLOW를 내줘서 fail-close가 캐시 레벨에서 뚫린다.
                String cachedVerdict = "SUCCESS".equals(outcome.status()) ? outcome.verdict() : "ERROR";
                FileVerdict fileVerdict = cached
                        .map(fv -> {
                            fv.recordAnalysis(
                                    cachedVerdict, SOURCE_ENGINE, outcome.rulesetsVersion(),
                                    outcome.detectedFileType(), Instant.now());
                            return fv;
                        })
                        .orElseGet(() -> FileVerdict.create(
                                command.verifiedSha256(),
                                command.fileSize(),
                                outcome.detectedFileType(),
                                cachedVerdict,
                                SOURCE_ENGINE,
                                outcome.rulesetsVersion(),
                                Instant.now()));
                fileVerdictRepository.save(fileVerdict);
                if (quarantineUpload != null) {
                    // file_verdicts가 방금 저장된 뒤라야 FK가 만족된다 — 순서 중요.
                    pendingQuarantineUploadPort.save(
                            command.verifiedSha256(), command.fileSize(), quarantineUpload.bucket(),
                            quarantineUpload.key());
                }
                saveAnalysis(command.verifiedSha256(), outcome);
                return toResult(outcome);
            });
        } catch (DataIntegrityViolationException e) {
            if (cached.isPresent()) {
                throw e; // 기존 행 갱신 중 충돌이면 레이스가 아니라 진짜 문제 — 그대로 던진다.
            }
            // 같은 새 파일이 동시에 두 번 들어와서, 먼저 커밋된 쪽이 file_verdicts PK를 이미 차지한
            // 경우다. 우리 쪽 분석이 틀린 게 아니라 그냥 늦게 도착한 것뿐이니, 먼저 커밋된 캐시를
            // 그대로 신뢰해서 응답한다(우리 Analysis 행은 포기 — 감사 이력 1건 누락은 레이스의
            // 부작용으로 받아들인다).
            FileVerdict winner = transactionTemplate
                    .execute(status -> fileVerdictRepository.findBySha256(command.verifiedSha256()))
                    .orElseThrow(() -> e);
            SubmitFileResult fromCache = fromCachedVerdict(winner);
            return fromCache != null ? fromCache : toResult(outcome);
        }
    }

    private void recordEngineFailure(
            String sha256, long fileSize, Optional<FileVerdict> cached, EngineUnavailableException e) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                FileVerdict fileVerdict = cached
                        .map(fv -> {
                            fv.recordAnalysis("ERROR", SOURCE_ENGINE, null, null, Instant.now());
                            return fv;
                        })
                        .orElseGet(() -> FileVerdict.create(
                                sha256, fileSize, null, "ERROR", SOURCE_ENGINE, null, Instant.now()));
                fileVerdictRepository.save(fileVerdict);

                Analysis analysis = Analysis.record(
                        sha256, null, FALLBACK_RULESETS_VERSION, "unavailable", "TIMEOUT", "ERROR", null, false,
                        null, 0, e.getMessage());
                analysisRepository.save(analysis);
            });
        } catch (DataIntegrityViolationException ignored) {
            // 같은 새 파일이 동시에 들어와 이미 기록됐다 — 우리 쪽 실패 기록은 포기해도 안전하다
            // (어느 쪽이든 이 해시는 다음 CheckHash/SubmitFile에서 캐시 미스로 재시도된다).
        }
    }

    private void saveAnalysis(String sha256, AnalysisOutcome outcome) {
        Analysis analysis = Analysis.record(
                sha256,
                // analyses.event_id -> download_events FK. SubmitFile은 보류가 끝나기 전에 실행되고,
                // download_events 행은 ReportEvent가 그 이후에나 만든다 — 지금 이 시점엔 아직 존재할 수
                // 없는 행이라 항상 NULL로 남긴다(스키마 주석의 "이벤트 없이 재검사한 경우"에 해당).
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
    }

    private SubmitFileResult fromCachedVerdict(FileVerdict fileVerdict) {
        return switch (fileVerdict.getVerdict()) {
            case "CLEAN" -> new SubmitFileResult("ALLOW", SOURCE_CACHE, null, List.of());
            case "MALICIOUS", "SUSPICIOUS" -> new SubmitFileResult(
                    "BLOCK", SOURCE_CACHE, "cached verdict: " + fileVerdict.getVerdict(), List.of());
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
