package com.teecherteamc.platform.ruleset.domain;

/** 엔진이 컴파일해서 돌려준 룰 하나의 이름과 심각도. */
public record CompiledRule(String name, String severity) {
}
