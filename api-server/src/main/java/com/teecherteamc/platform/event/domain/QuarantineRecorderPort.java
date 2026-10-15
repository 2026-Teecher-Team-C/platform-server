package com.teecherteamc.platform.event.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * quarantine_files 행을 만드는 포트. quarantine 컨텍스트 소유 테이블이지만, 이 행을 실제로
 * 만들 수 있는 유일한 시점(download_events가 막 생긴 직후)이 event 컨텍스트 안이라, event가
 * 자기 포트로 직접 쓴다.
 *
 * <p><b>알려진 CLAUDE.md 위반(의도적, 임시):</b> "다른 컨텍스트의 애그리거트를 직접 수정하지
 * 않고 도메인 이벤트로 알린다"는 규칙과 다르게, 여기는 raw JDBC로 quarantine 소유 테이블에
 * 직접 INSERT한다. FileVerdictExistsPort류의 기존 "소비 측 얇은 포트" 컨벤션은 전부 읽기 전용이라
 * 이 규칙에 안 걸리지만, 이건 쓰기라서 문자 그대로는 위반이다. 지금 이 레포에 도메인 이벤트
 * 인프라(ApplicationEventPublisher 등) 자체가 전혀 없어서, 이 PR 하나만을 위해 이벤트 버스를
 * 새로 들이는 건 과한 선행 투자로 판단해 미뤘다. quarantine/domain의 실제 애그리거트와 도메인
 * 이벤트 발행은 격리 목록/상세/복원 API(quarantine 컨텍스트를 처음 본격 구현하는 시점)에서
 * 함께 만들고, 그때 이 raw INSERT를 이벤트 기반으로 교체할 것.
 */
public interface QuarantineRecorderPort {

    void record(
            UUID eventId,
            String sha256,
            UUID agentId,
            String originalFilename,
            long fileSize,
            String bucket,
            String key,
            Instant now);
}
