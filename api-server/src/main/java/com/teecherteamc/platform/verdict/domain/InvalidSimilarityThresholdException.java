package com.teecherteamc.platform.verdict.domain;

/**
 * 유사도 임계값이 허용 범위를 벗어났을 때 던진다.
 * 메시지는 관리자에게 그대로 보여 주는 문장이다. 인터페이스 계층이 400(INVALID_INPUT)으로 바꾼다.
 */
public class InvalidSimilarityThresholdException extends RuntimeException {

    public InvalidSimilarityThresholdException(String message) {
        super(message);
    }
}