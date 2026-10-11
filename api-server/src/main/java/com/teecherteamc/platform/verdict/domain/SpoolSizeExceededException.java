package com.teecherteamc.platform.verdict.domain;

/**
 * 스풀 상한(지금은 정책 대신 하드코딩된 안전 상한 — policy 컨텍스트의 GetPolicy가 생기면
 * max_inspect_size_bytes로 대체될 예정, Sprint 4)을 넘었을 때 던진다.
 */
public class SpoolSizeExceededException extends RuntimeException {

    public SpoolSizeExceededException(String message) {
        super(message);
    }
}
