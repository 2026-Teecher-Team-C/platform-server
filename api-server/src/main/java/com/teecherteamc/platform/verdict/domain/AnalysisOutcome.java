package com.teecherteamc.platform.verdict.domain;

import java.util.List;

/**
 * 탐지 엔진 Analyze 호출 1회의 결과. status: SUCCESS/TIMEOUT/CRASH/OOM/UNSUPPORTED.
 * verdict: CLEAN/MALICIOUS/SUSPICIOUS/UNKNOWN/ERROR. maxSectionEntropy는 아직 엔진이 안 채우면 null.
 */
public record AnalysisOutcome(
        String status,
        String verdict,
        String engineVersion,
        Integer rulesetsVersion,
        String detectedFileType,
        boolean peParsed,
        Double maxSectionEntropy,
        List<EngineMatch> matches,
        int durationMs,
        String errorMessage) {}
