package com.teecherteamc.platform.verdict.domain;

/**
 * 에이전트가 주장한 sha256과 서버가 스트리밍 중 독립적으로 재계산한 sha256이 다를 때 던진다.
 * 이 시스템의 신뢰 경계 — 에이전트가 보낸 해시를 그대로 믿지 않는다.
 */
public class HashMismatchException extends RuntimeException {

    public HashMismatchException(String claimed, String actual) {
        super("claimed sha256=%s but computed=%s".formatted(claimed, actual));
    }
}
