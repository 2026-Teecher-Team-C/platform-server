package com.teecherteamc.platform.ruleset.domain;

/**
 * 엔진이 쓰는 룰 구성이 바뀌었다는 도메인 이벤트(활성 버전이 바뀌었거나, 활성 버전의 룰 사용 여부가 바뀜).
 * verdict 컨텍스트는 엔진 판정을 stale로 표시하고, 엔진 적재는 커밋 후에 따로 한다.
 */
public record RulesetDetectionChanged(int version) {
}
