package com.teecherteamc.platform.verdict.domain;

import java.util.List;

/**
 * 탐지 엔진 Analyze 호출 1회의 결과. status: SUCCESS/TIMEOUT/CRASH/OOM/UNSUPPORTED.
 * verdict: CLEAN/MALICIOUS/SUSPICIOUS/UNKNOWN/ERROR. maxSectionEntropy는 아직 엔진이 안 채우면 null.
 * rulesetKey는 엔진이 이 판정에 실제로 쓴 룰셋 키다(엔진이 보내지 않으면 빈 문자열).
 */
public record AnalysisOutcome(
        String status,
        String verdict,
        String engineVersion,
        Integer rulesetsVersion,
        String rulesetKey,
        String detectedFileType,
        boolean peParsed,
        Double maxSectionEntropy,
        List<EngineMatch> matches,
        int durationMs,
        String errorMessage) {}
