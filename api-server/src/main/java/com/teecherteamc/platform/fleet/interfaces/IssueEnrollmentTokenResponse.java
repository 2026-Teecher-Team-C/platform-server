package com.teecherteamc.platform.fleet.interfaces;

import com.teecherteamc.platform.fleet.application.IssueEnrollmentTokenUseCase;
import java.time.Instant;

/**
 * 201 Created 응답 본문. 콘솔의 CreateEnrollmentTokenResponse({ token, expiresAt })와 필드 이름이 같다.
 *
 * <p>token은 평문이고, 이 응답이 평문을 볼 수 있는 유일한 곳이다. 다시 조회하는 API가 없으니
 * 이 클래스에 토큰 id나 해시 같은 필드를 더하지 않는다.
 *
 * <p>expiresAt은 Instant라서 JSON에서 "2026-10-20T02:00:00Z" 같은 UTC 문자열로 나간다
 * (에이전트 목록의 lastHeartbeatAt과 같은 방식).
 */
public record IssueEnrollmentTokenResponse(String token, Instant expiresAt) {

    /** 유스케이스 결과를 응답 모양으로 바꾼다. 컨트롤러가 도메인/애플리케이션 타입을 그대로 내보내지 않게 하려는 변환이다. */
    public static IssueEnrollmentTokenResponse from(IssueEnrollmentTokenUseCase.Result result) {
        return new IssueEnrollmentTokenResponse(result.token(), result.expiresAt());
    }
}