package com.teecherteamc.platform.verdict.application;

import java.nio.file.Path;

/**
 * interfaces 레이어가 스트림을 다 받아서 스풀 파일을 완성한 뒤 이 커맨드를 만든다.
 * filename/mime_type은 analyses·file_verdicts 어디에도 저장되지 않아(ReportEvent 쪽 소관) 여기 없다.
 * event_id도 안 들어있다 — interfaces가 형식만 검증하고, analyses.event_id는 FK 순서 문제로 항상
 * NULL로 저장되니 넘길 이유가 없다.
 */
public record SubmitFileCommand(String claimedSha256, String verifiedSha256, long fileSize, Path spoolFile) {}
