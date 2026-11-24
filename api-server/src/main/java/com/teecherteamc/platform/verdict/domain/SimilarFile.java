package com.teecherteamc.platform.verdict.domain;

/** 기준 TLSH와의 거리가 임계값 이하인 지문 한 건. sha256은 소문자 16진수 64자(file_fingerprints의 PK)다. */
public record SimilarFile(String sha256, int distance) {}
