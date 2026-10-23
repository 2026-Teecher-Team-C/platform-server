# 세션 쿠키와 서드파티 쿠키 차단

관리 콘솔 로그인(세션 쿠키, PR #30)이 브라우저 설정에 따라 동작하지 않을 수 있다는 문제와 해결 방안을 정리한다.
**결정이 아직 나지 않았다.** 정해지면 이 문서의 "결정" 절을 채운다.

## 1. 문제

콘솔과 API 서버가 서로 다른 사이트에 있다.

| | 주소 | 사이트(eTLD+1) |
|---|---|---|
| 관리 콘솔 | `https://teecher-console.vercel.app` | `teecher-console.vercel.app` (`vercel.app`은 공개 접미사라 하위 도메인 자체가 한 사이트다) |
| API 서버 | `https://teamc-platform.duckdns.org` | `teamc-platform.duckdns.org` (`duckdns.org`도 공개 접미사) |

콘솔 화면에서 API를 부르면 브라우저는 API 서버가 준 세션 쿠키를 **서드파티 쿠키**로 취급한다.
그래서 운영 쿠키를 `SameSite=None; Secure; HttpOnly`로 내리고 있다(`application.yml`의 `server.servlet.session.cookie`).
`SameSite=None`이어도 서드파티 쿠키 자체를 막는 브라우저·설정에서는 쿠키가 저장되지 않거나 다음 요청에 실리지 않는다.

### 증상

1. `POST /api/admin/auth/login`은 200으로 성공한다
2. 그다음 `GET /api/admin/auth/me`나 다른 관리 API가 401(`UNAUTHORIZED`)을 받는다
3. 콘솔은 401을 받으면 로그인 화면으로 돌려보내므로, "로그인해도 계속 로그인 화면으로 돌아간다"로 보인다

### 영향 받는 환경 (공개된 브라우저 정책 기준, 우리가 직접 확인하지는 않았다)

| 브라우저 | 기본 동작 | 예상 |
|---|---|---|
| Chrome 일반 모드 | 서드파티 쿠키 허용 | 동작한다 |
| Chrome 시크릿 모드 | 서드파티 쿠키 차단 | **로그인 유지 안 됨** |
| Chrome에서 "서드파티 쿠키 차단"을 켠 경우 | 차단 | **로그인 유지 안 됨** |
| Safari | 추적 방지(ITP)로 서드파티 쿠키 차단 | **로그인 유지 안 됨** |
| Firefox | 서드파티 쿠키를 최상위 사이트별로 나눠 저장(Total Cookie Protection) | 항상 콘솔에서만 부르므로 동작할 가능성이 있다. 미확인 |

**시연을 Chrome 일반 모드로만 한다면 지금 구조로도 동작할 가능성이 높다.** 시연 PC·브라우저가 바뀌면 깨질 수 있다.

## 2. 해결 방안

### A. Vercel rewrite로 같은 출처로 만들기 (추천)

콘솔이 API를 `https://teamc-platform.duckdns.org/api/...`가 아니라 **자기 출처의 `/api/...`로 부르고**,
Vercel이 그 요청을 EC2로 넘긴다. 브라우저 입장에서는 콘솔과 API가 같은 출처라 쿠키가 퍼스트파티가 된다.

바꿀 것:

1. `console/vercel.json`의 SPA rewrite **앞에** API rewrite를 넣는다 (순서가 중요하다. 지금 있는 `/(.*)` → `/index.html`이 먼저 오면 API 요청까지 index.html로 간다)
   ```json
   {
     "rewrites": [
       { "source": "/api/:path*", "destination": "https://teamc-platform.duckdns.org/api/:path*" },
       { "source": "/(.*)", "destination": "/index.html" }
     ]
   }
   ```
2. Vercel 환경변수 `VITE_API_BASE_URL`을 빈 문자열로 둔다 (`client.ts`가 `API_BASE_URL + path`로 부르므로 같은 출처 `/api/...`가 된다)
3. 운영 쿠키를 `SameSite=Lax`로 낮춘다 (EC2 `infra/.env`에 `SESSION_COOKIE_SAME_SITE=lax`). `Secure`, `HttpOnly`는 유지
4. CORS는 더 이상 필요 없다. 그래도 로컬 개발(5173 → 8080)용으로 설정은 남겨 둔다
5. `OriginCheckFilter`는 그대로 둔다. 브라우저가 보내는 Origin은 여전히 `https://teecher-console.vercel.app`이라 허용 목록과 맞는다

장점: 브라우저 정책과 무관하게 동작한다. 코드 변경이 거의 없다(설정 몇 줄).

확인해야 할 것:

- **SSE(`/api/admin/events/stream`)가 Vercel을 거쳐 오래 유지되는지 미확인.** Vercel 외부 rewrite의 응답 스트리밍·연결 시간 제한을 실제로 확인해야 한다. 끊기면 콘솔이 재연결하거나 폴링으로 돌아가게 만들어 두면 된다
- **감사 로그 IP가 Vercel 서버 주소로 바뀐다.** nginx가 보는 `$remote_addr`가 사용자 PC가 아니라 Vercel이 되기 때문이다. 사용자 IP가 필요하면 Vercel이 넘기는 헤더를 믿는 설정을 따로 해야 하는데, 그러면 헤더 위조 방어를 다시 따져야 한다
- 요청이 Vercel을 한 번 더 거치므로 지연이 조금 늘어난다

### B. 콘솔과 API를 같은 상위 도메인에 두기

예: `console.example.com`(Vercel), `api.example.com`(EC2). 같은 사이트라 `SameSite=Lax`로 충분하고 프록시가 없다.

- 도메인 구매, DNS 설정, Vercel 사용자 도메인 연결, EC2 인증서 재발급이 필요하다
- 가장 정석이지만 2주 일정에는 무겁다

### C. 지금 구조 유지 (`SameSite=None`)

- 시연은 Chrome 일반 모드로만 한다
- 비용은 없지만 위 표의 환경에서는 로그인이 깨진다

### D. 분할 쿠키(CHIPS, `Partitioned` 속성)

서드파티 쿠키를 최상위 사이트별로 나눠 저장하게 하는 속성이다. 서드파티 쿠키 차단 환경에서도 허용되는 경우가 있다.

- 브라우저별 지원과 Spring의 `Set-Cookie` 설정 방법을 확인하지 않았다. 지원이 확실하지 않아 1순위로 두지 않는다

## 3. 비교

| | 브라우저 무관 동작 | 작업량 | 남는 문제 |
|---|---|---|---|
| A. Vercel rewrite | O | 작다 (설정) | SSE 유지 확인, 감사 로그 IP |
| B. 같은 상위 도메인 | O | 크다 (도메인·DNS·인증서) | 없음 |
| C. 유지 | X | 없다 | Safari·시크릿에서 로그인 불가 |
| D. CHIPS | 미확인 | 작다 | 지원 범위 미확인 |

## 4. 결정

- [ ] 결정 내용:
- [ ] 결정일·결정한 사람:

## 관련

- `docs/auth-policy.md` — 세션 쿠키 방침
- `config/OriginCheckFilter.java` — 쿠키 기반 CSRF 토큰 대신 Origin으로 막는 이유
- `application.yml` `server.servlet.session.cookie`, `infra/compose.yml` `SESSION_COOKIE_*`
