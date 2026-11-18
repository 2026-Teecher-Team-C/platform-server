package com.teecherteamc.platform.verdict.interfaces;

import jakarta.validation.constraints.NotNull;

/**
 * PUT /api/admin/similarity-policy 요청 본문.
 * 필드가 빠졌는지만 여기서 거르고, 값의 범위(차단 0~50, 경고는 차단보다 크고 100 이하)는
 * 도메인(SimilarityThresholds)이 한 곳에서 검증한다.
 */
public record UpdateSimilarityThresholdRequest(
        @NotNull Integer blockDistance,
        @NotNull Integer warnDistance) {
}