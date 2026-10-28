package com.teecherteamc.platform.ruleset.domain;

import java.util.List;

/** 룰 원문 컴파일 결과. ok=false면 errorMessage에 이유가 들어있다. */
public record CompileResult(boolean ok, String errorMessage, List<CompiledRule> rules) {
}
