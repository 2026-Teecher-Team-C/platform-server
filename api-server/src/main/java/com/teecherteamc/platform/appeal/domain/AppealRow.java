package com.teecherteamc.platform.appeal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * appeals를 agents·devices(호스트명), download_events(파일명·차단 시각), admin_users(결정자)와 조인한 읽기 전용 행.
 * JPA 엔티티가 아니다. 이벤트 행이 아직 없으면 filename·blockedAt이 null이다.
 */
public record AppealRow(
        UUID appealId,
        String status,
        Instant createdAt,
        Instant decidedAt,
        String hostname,
        String filename,
        String sha256,
        String evidenceKind,
        String threat,
        String reason,
        UUID eventId,
        UUID agentId,
        String decisionNote,
        String decidedBy,
        boolean blacklisted,
        Instant blockedAt) {
}
