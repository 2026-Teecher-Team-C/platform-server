package com.teecherteamc.platform.sample.domain;

import java.time.Instant;

/** 현재 저장된 메타데이터. 이벤트 당시의 유사도 스냅샷과 별개다. */
public record SampleDetail(String sha256, Blacklist blacklist, Fingerprint fingerprint) {
    public record Blacklist(String reason, String source, String severity, boolean isActive, Instant registeredAt) {}
    public record Fingerprint(String tlsh, String calculationStatus, String algorithmVersion,
                              String provenance, String provenanceDetail, Instant calculatedAt) {}
}
