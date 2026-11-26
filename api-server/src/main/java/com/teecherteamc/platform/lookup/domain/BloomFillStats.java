package com.teecherteamc.platform.lookup.domain;

/** live 블룸의 채움 상태. 추정 오탐률은 표준 근사 (켜진 비트 비율)^k 이다. */
public record BloomFillStats(long setBits, long bitSize, int hashCount) {

    public double fillRatio() {
        return (double) setBits / bitSize;
    }

    public double estimatedFpp() {
        return Math.pow(fillRatio(), hashCount);
    }
}
