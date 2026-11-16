package com.teecherteamc.platform.event.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * download_events + devices.hostname 조인 결과 1행. 목록 전용 읽기 모델 — JPA 엔티티가 아니다.
 *
 * <p>severity·topRuleName·extraRuleCount는 download_event_threats 뷰(V7·V12)에서 오고, 차단(BLOCKED)일 때만 채운다.
 * acknowledged는 차단(BLOCKED·FAIL_CLOSE)이면서 확인 행이 있을 때만 true다.
 */
public record DownloadEventListRow(
        UUID eventId,
        UUID agentId,
        String hostname,
        String sha256,
        String filename,
        String mimeType,
        Long fileSize,
        String decision,
        String decisionSource,
        boolean cacheHit,
        Integer holdDurationMs,
        Instant createdAt,
        String similarityClassification,
        String severity,
        String topRuleName,
        int extraRuleCount,
        boolean acknowledged) {
}
