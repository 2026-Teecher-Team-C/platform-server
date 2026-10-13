package com.teecherteamc.platform.verdict.application;

import java.util.List;

/** decision: ALLOW 또는 BLOCK뿐이다(SubmitFile은 UNKNOWN을 돌려줄 수 없다 — 더 올려볼 데가 없다). */
public record SubmitFileResult(String decision, String source, String reason, List<String> matchedRules) {}
