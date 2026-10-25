# 인증 방침

api-server의 로그인·권한 적용 방침이다. 관리 콘솔 로그인(Bearer 토큰)은 구현됐다(7장).
역할별 권한과 에이전트 업로드 인증은 아직이다.

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
- POST 요청에 CSRF 토큰이 필요해진다. Bearer 토큰만 쓰고 세션을 만들지 않으면(STATELESS) 끈다.
- `GlobalExceptionHandler`의 `Exception` 처리가 `AccessDeniedException`, `AuthenticationException`까지 잡으면 401/403이 500으로 바뀐다. 이 예외들은 다시 던지거나 따로 처리한다.
- 401/403 응답은 Security의 진입점(`AuthenticationEntryPoint`)과 접근 거부 처리기(`AccessDeniedHandler`)에서 `ErrorResponse` 형식으로 맞춘다.
- CORS 설정을 Security 필터 체인과 함께 정리한다. 허용 도메인은 정확히 지정하고, `Authorization` 헤더를 허용한다.

## 4. 로그인 방식과 SSE 인증

관리 콘솔 로그인은 Bearer 토큰을 쓴다. (기능 API 명세 "로그인" 기준. 2026-10-18 세션 쿠키 결정을 2026-10-25 바꿨다)

- 로그인하면 access 토큰(JWT)과 refresh 토큰을 준다. 관리 API는 `Authorization: Bearer <access 토큰>`으로 부른다.
- 쿠키를 쓰지 않으므로 브라우저가 자격 증명을 자동으로 싣지 않는다. 그래서 CSRF 방어가 필요 없고, 콘솔(Vercel)과 api-server가 다른 사이트여도 서드파티 쿠키 차단의 영향을 받지 않는다.
- CORS는 허용 도메인을 정확한 주소로 지정하고 `Authorization` 헤더를 허용한다. 자격 증명(`allowCredentials`)은 허용하지 않는다.
- SSE: `EventSource`는 `Authorization` 헤더를 못 싣는다. 쿼리 토큰 등 방식은 SSE를 구현할 때 정한다.

## 5. 에이전트 업로드 인증

에이전트가 자동으로 올리는 업로드와, 불가피한 상황에서 사람이 수동으로 올리는 업로드는 같은 인증 방식으로 처리한다. (10/15 팀 결정)

- 에이전트는 gRPC로 붙는다. 현재는 수동 `AGENT_TOKEN`을 쓴다.
- 인증 값을 무엇으로 할지(토큰, 세션 등)는 구현할 때 정한다.

## 6. 미정

- 에이전트 업로드 인증의 구체적인 방식과 토큰 발급·관리 방법
- SSE 인증 방식 (4장)

## 7. 구현 (관리 콘솔 로그인)

| API | 설명 |
|---|---|
| `POST /api/admin/auth/login` | `{username, password}` → 200 `{accessToken, refreshToken, tokenType: "Bearer"}`. 없는 계정·틀린 비밀번호·비활성 계정은 모두 같은 401 |
| `POST /api/admin/auth/refresh` | `{refreshToken}` → 200 새 토큰 한 쌍. 쓴 refresh 토큰은 폐기된다(한 번만 쓸 수 있다). 모르는·만료된·쓴 토큰이거나 비활성 계정이면 401 |
| `POST /api/admin/auth/logout` | 로그인 필요. 본문 `{refreshToken}`(선택)을 폐기하고 204. 본인 토큰만 폐기한다 |
| `GET /api/admin/auth/me` | 현재 관리자 `{adminId, username, role, isActive, lastLoginAt}`. access 토큰이 유효해도 그사이 비활성화됐으면 401 |

- 로그인 없이 열린 곳: 로그인·refresh API, 시연용 `GET /api/admin/download-events`, actuator `health`·`prometheus`. 나머지는 로그인이 필요하다.
- access 토큰: HS256 JWT. `sub`=admin_id, `username` 클레임. role·is_active는 넣지 않고 매번 DB에서 읽는다. 서버에 상태가 없어서 로그아웃해도 만료 전까지는 유효하다 — 그래서 짧게 둔다.
- refresh 토큰: JWT가 아닌 랜덤 문자열. Redis에 SHA-256 해시로 저장한다(`admin:refresh:<해시>`). Redis가 죽으면 로그인·refresh가 실패한다.
- 로그인·로그아웃·로그인 실패는 감사 로그(`LOGIN` / `LOGOUT` / `LOGIN_FAILED`)로 남는다. IP는 nginx가 덮어쓴 `X-Forwarded-For`의 첫 주소다.

| 환경 변수 | 운영 기본값 | 설명 |
|---|---|---|
| `JWT_SECRET` | 없음(필수) | access 토큰 서명 키. 32바이트 이상. 없거나 짧으면 기동하지 않는다 |
| `JWT_ACCESS_TOKEN_TTL` | `1h` | access 토큰 유효 기간 |
| `JWT_REFRESH_TOKEN_TTL` | `7d` | refresh 토큰 유효 기간 |
| `ADMIN_BOOTSTRAP_USERNAME` / `ADMIN_BOOTSTRAP_PASSWORD` | 없음 | 둘 다 있고 그 계정명이 없을 때만 기동 시 `ADMIN` 계정을 만든다. 이미 있으면 비밀번호를 바꾸지 않는다 |
