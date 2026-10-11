package com.teecherteamc.platform.lookup.application;

/** decision: ALLOW/BLOCK/UNKNOWN. source는 UNKNOWN일 때 null. interfaces 레이어가 proto로 매핑한다. */
public record CheckHashResult(String decision, String source, String reason) {}
