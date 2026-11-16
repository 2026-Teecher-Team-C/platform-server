package com.teecherteamc.platform.event.domain;

/**
 * file_verdicts(sha256) 존재 여부만 확인하는 포트. verdict 컨텍스트 소유 테이블을 읽기 전용으로 참조한다.
 *
 * <p>ReportEvent의 sha256 보존 정책에 필요하다: file_verdicts 행이 있거나 이벤트에 연결된 평가 근거가
 * 있을 때만 sha256을 남기고, 아니면 null로 저장한다(download_events.sha256 FK는 V14에서 제거됨).
 */
public interface FileVerdictExistsPort {

    boolean exists(String sha256);

    /** 이 이벤트에 같은 에이전트·SHA로 예약된 근거(평가)가 연결돼 있는지. 업로드 전 TLSH 차단은 file_verdicts 없이 이것만 있다. */
    boolean hasEvidenceAssessment(java.util.UUID eventId, java.util.UUID agentId, String sha256);
}
