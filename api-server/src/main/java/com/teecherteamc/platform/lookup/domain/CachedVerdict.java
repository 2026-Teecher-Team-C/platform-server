package com.teecherteamc.platform.lookup.domain;

/** file_verdicts 한 행을 조회 사슬이 쓰는 데 필요한 만큼만 담은 읽기 전용 값. */
public record CachedVerdict(String verdict, boolean stale) {}
