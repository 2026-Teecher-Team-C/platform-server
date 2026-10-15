# 인증 방침

api-server의 로그인·권한 적용 방침이다. 지금은 문서만 있고, 실제 구현은 마무리 단계에서 한다.
기능 개발과 테스트에 지장을 줄 수 있어서 최후순위로 미뤘다. (10/15 회의 결정)

## 1. 적용 범위

- 헬스체크를 제외한 모든 엔드포인트에 인증을 적용한다.
- 시연용으로 인증 없이 공개한 엔드포인트(다운로드 이벤트 목록 API)는 시연이 끝나면 바로 막는다.
- 관리자 계정은 현재 모두 같은 권한을 가진다. 역할(`VIEWER` / `ANALYST` / `ADMIN`)은 나중에 필요해지면 나눈다.

## 2. 에러 응답

인증 실패와 권한 부족도 공통 에러 응답 형식(`ErrorResponse`)으로 내려간다. ([API 컨벤션](api-convention.md) 참고)

| 상황 | HTTP | code |
|---|---|---|
| 로그인하지 않음 | 401 | `UNAUTHORIZED` |
| 권한 없음 | 403 | `FORBIDDEN` |

## 3. Security를 추가할 때 주의할 점

의존성을 추가하면 아래가 동시에 바뀐다. 한 번에 처리해야 한다.

- 모든 HTTP 엔드포인트가 기본으로 401이 된다. 헬스체크를 예외로 열어 줘야 한다.
- POST 요청에 CSRF 토큰이 필요해진다. 쿠키 세션을 쓰면 프론트 요청 방식도 같이 확인한다.
- `GlobalExceptionHandler`의 `Exception` 처리가 `AccessDeniedException`, `AuthenticationException`까지 잡으면 401/403이 500으로 바뀐다. 이 예외들은 다시 던지거나 따로 처리한다.
- 401/403 응답은 Security의 진입점(`AuthenticationEntryPoint`)과 접근 거부 처리기(`AccessDeniedHandler`)에서 `ErrorResponse` 형식으로 맞춘다.
- CORS 설정을 Security 필터 체인과 함께 정리한다. 지금은 api-server에서 허용 도메인만 설정하고 있다.

## 4. 로그인 방식과 SSE 인증

관리 콘솔 로그인은 세션 쿠키를 쓰고, SSE도 같은 쿠키를 쓴다. (현재 방향이고, 구현할 때 팀과 확정한다)

- `EventSource`는 `Authorization` 헤더를 못 보내지만 쿠키는 보낼 수 있어서, 로그인과 SSE를 한 방식으로 맞출 수 있다.
- 콘솔(Vercel)과 api-server(EC2 도메인)가 서로 다른 사이트라서, 쿠키를 주고받으려면 아래 설정이 같이 필요하다.
  - 쿠키는 `SameSite=None; Secure`로 내리고, HTTPS에서만 동작한다.
  - CORS는 허용 도메인을 `*`가 아니라 정확한 주소로 지정하고, 자격 증명(`allowCredentials`)을 허용한다.
  - 프론트는 요청에 쿠키를 싣는 옵션(`credentials: 'include'`, `EventSource`는 `withCredentials: true`)을 켠다.
- 세션 쿠키를 쓰면 POST 요청에 CSRF 방어가 필요하다. CSRF 토큰을 쓸지, `SameSite` 설정으로 막을지 정한다.
- 에이전트 요청에서 쿠키 적용이 어려우면 쿼리 토큰을 대안으로 쓴다.   <- 여기에 추가

## 5. 에이전트 업로드 인증

에이전트가 자동으로 올리는 업로드와, 불가피한 상황에서 사람이 수동으로 올리는 업로드는 같은 인증 방식으로 처리한다. (10/15 팀 결정)

- 에이전트는 gRPC로 붙는다. 현재는 수동 `AGENT_TOKEN`을 쓴다.
- 인증 값을 무엇으로 할지(토큰, 세션 등)는 구현할 때 정한다.

## 6. 미정

- 에이전트 업로드 인증의 구체적인 방식과 토큰 발급·관리 방법