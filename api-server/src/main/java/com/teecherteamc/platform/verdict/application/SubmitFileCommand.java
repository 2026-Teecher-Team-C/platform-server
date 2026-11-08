package com.teecherteamc.platform.verdict.application;

import java.nio.file.Path;
import java.util.UUID;

/**
 * interfaces 레이어가 스트림을 다 받아서 스풀 파일을 완성한 뒤 이 커맨드를 만든다.
 * filename/mime_type은 analyses·file_verdicts 어디에도 저장되지 않아(ReportEvent 쪽 소관) 여기 없다.
 * event_id는 ReportEvent 이전에 근거를 보관하는 키다. 존재하지 않는 이벤트 FK 대신 pending 매핑을 쓴다.
 */
public record SubmitFileCommand(String claimedSha256, String verifiedSha256, long fileSize, Path spoolFile, UUID eventId, UUID agentId) {
    public SubmitFileCommand(String claimedSha256, String verifiedSha256, long fileSize, Path spoolFile) {
        this(claimedSha256, verifiedSha256, fileSize, spoolFile, null, null);
    }
}
