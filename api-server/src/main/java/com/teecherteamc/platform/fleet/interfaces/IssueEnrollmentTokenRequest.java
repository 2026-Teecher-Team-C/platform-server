package com.teecherteamc.platform.fleet.interfaces;

/**
 * POST /api/admin/agents/enrollment-tokens 요청 본문.
 *
 * <p>expiresInMinutes는 일부러 객체 타입(Long)으로 받는다. 원시 타입(long)이면 값을 안 보냈을 때
 * 0이 되어 "안 보냄"과 "0을 보냄"을 구분할 수 없다. 안 보내면 null로 들어오고, 컨트롤러가
 * 기본값(60분)을 적용한다.
 *
 * <p>범위 검증(1 이상, 상한 이하)은 여기에 애노테이션으로 걸지 않고 컨트롤러에서 한다.
 * 다른 목록·등록 API와 똑같이 잘못된 값을 ApiException(INVALID_INPUT, 400)으로 통일하기 위해서다.
 */
public record IssueEnrollmentTokenRequest(Long expiresInMinutes) {}