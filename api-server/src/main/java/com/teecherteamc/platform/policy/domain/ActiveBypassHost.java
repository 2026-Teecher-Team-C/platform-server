package com.teecherteamc.platform.policy.domain;

/**
 * 에이전트 GetPolicy에 내려갈 활성 바이패스 호스트 한 건. 읽기 전용이라 BypassDomain 엔티티를 안 거친다.
 * category를 문자열이 아닌 enum으로 들고 있어, proto 변환 switch에서 값 누락을 컴파일러가 잡는다.
 */
public record ActiveBypassHost(String host, BypassCategory category) {
}
