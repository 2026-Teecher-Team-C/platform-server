package com.teecherteamc.platform.verdict.domain;

import java.util.List;

public record EngineMatch(String ruleName, String severity, List<String> matchedStrings) {}
