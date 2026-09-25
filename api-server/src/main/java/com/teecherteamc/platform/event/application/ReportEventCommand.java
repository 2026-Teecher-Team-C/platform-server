package com.teecherteamc.platform.event.application;

import java.time.Instant;
import java.util.UUID;

/** interfaces 레이어가 proto 요청을 이 커맨드로 변환해서 넘긴다 — application/domain은 proto 타입을 모른다. */
public record ReportEventCommand(
        UUID eventId,
        UUID agentId,
        String sha256,
        String requestHost,
        String url,
        String filename,
        String mimeType,
        String contentDisposition,
        Long fileSize,
        String decision,
        String decisionSource,
        Boolean cacheHit,
        long bytesUploaded,
        Instant heldAt,
        Instant decidedAt) {}
