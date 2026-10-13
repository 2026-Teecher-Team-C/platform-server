package com.teecherteamc.platform.verdict.domain;

import java.nio.file.Path;

/** 탐지 엔진(별도 gRPC 서비스)에 스풀 파일을 보내 분석시키는 포트. */
public interface EngineAnalysisPort {

    AnalysisOutcome analyze(Path spoolFile, long fileSize);
}
