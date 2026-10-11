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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubmitFileUseCase {

    private static final String SOURCE_ENGINE = "ENGINE";
    private static final String SOURCE_CACHE = "CACHE";
    // V2__seed_default_ruleset.sql이 심어둔 placeholder 행. 엔진 호출 자체가 실패해서 어떤 룰셋이었는지
    // 알 길이 없을 때, analyses.rulesets_version(NOT NULL)을 채우는 용도로 재사용한다.
    private static final int FALLBACK_RULESETS_VERSION = 0;

    private final FileVerdictRepository fileVerdictRepository;
    private final AnalysisRepository analysisRepository;
    private final EngineAnalysisPort engineAnalysisPort;

    public SubmitFileUseCase(
            FileVerdictRepository fileVerdictRepository,
            AnalysisRepository analysisRepository,
            EngineAnalysisPort engineAnalysisPort) {
        this.fileVerdictRepository = fileVerdictRepository;
        this.analysisRepository = analysisRepository;
        this.engineAnalysisPort = engineAnalysisPort;
    }

    // EngineUnavailableException은 잡아서 실패 기록을 남긴 뒤 그대로 다시 던지는데, 기본 롤백 정책이면
    // 그 기록까지 같이 날아간다 — 이 예외만 롤백 대상에서 뺀다.
    @Transactional(noRollbackFor = EngineUnavailableException.class)
    public SubmitFileResult submitFile(SubmitFileCommand command) {
        if (!command.verifiedSha256().equals(command.claimedSha256())) {
            // 해시 불일치를 알았으니, 에이전트가 주장한 값이 아니라 실제 내용을 기준으로 재검증한다.
            // 호출자(interfaces)가 이 예외를 잡아 DATA_LOSS로 거부하고 스풀을 지운다.
            throw new HashMismatchException(command.claimedSha256(), command.verifiedSha256());
        }

        Optional<FileVerdict> cached = fileVerdictRepository.findBySha256(command.verifiedSha256());
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
            outcome = engineAnalysisPort.analyze(command.spoolFile(), command.fileSize());
        } catch (EngineUnavailableException e) {
            // 엔진이 구조화된 응답조차 못 준 경우 — 이대로 두면 analyses에 아무 기록도 안 남는다.
            // 호출자(interfaces)가 UNAVAILABLE로 거부하도록 그대로 다시 던지되, 그 전에 감사 기록은
            // 남겨둔다(verdict=ERROR라 CheckHash/SubmitFile 둘 다 캐시 미스로 취급 — 다음 요청은
            // 다시 엔진을 부른다).
            recordEngineFailure(command.verifiedSha256(), command.fileSize(), cached, e);
            throw e;
        }

        FileVerdict fileVerdict = cached
                .map(fv -> {
                    fv.recordAnalysis(
                            outcome.verdict(), SOURCE_ENGINE, outcome.rulesetsVersion(), outcome.detectedFileType(),
                            Instant.now());
                    return fv;
                })
                .orElseGet(() -> FileVerdict.create(
                        command.verifiedSha256(),
                        command.fileSize(),
                        outcome.detectedFileType(),
                        outcome.verdict(),
                        SOURCE_ENGINE,
                        outcome.rulesetsVersion(),
                        Instant.now()));
        fileVerdictRepository.save(fileVerdict);

        Analysis analysis = Analysis.record(
                command.verifiedSha256(),
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

        return toResult(outcome);
    }

    private void recordEngineFailure(String sha256, long fileSize, Optional<FileVerdict> cached, EngineUnavailableException e) {
        FileVerdict fileVerdict = cached
                .map(fv -> {
                    fv.recordAnalysis("ERROR", SOURCE_ENGINE, null, null, Instant.now());
                    return fv;
                })
                .orElseGet(() -> FileVerdict.create(sha256, fileSize, null, "ERROR", SOURCE_ENGINE, null, Instant.now()));
        fileVerdictRepository.save(fileVerdict);

        Analysis analysis = Analysis.record(
                sha256,
                null,
                FALLBACK_RULESETS_VERSION,
                "unavailable",
                "TIMEOUT",
                "ERROR",
                null,
                false,
                null,
                0,
                e.getMessage());
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
