package com.teecherteamc.platform.dashboard.domain;

/** 차단 상위 PC(key=agentId, label=hostname) 또는 도메인(key=label=요청 호스트) 1줄. */
public record TopSourceRow(String key, String label, long blockCount) {
}
