# 콘솔 로그인과 토큰 수명

## 흐름

- 로그인 API는 기존 세션의 Authorization 없이 호출한다. 반환된 access와 refresh를 같은 탭의 `sessionStorage`에 저장하고 `/me`로 현재 관리자를 확인한다. 비밀번호는 저장하지 않는다.
- 보호된 요청이 401이면 refresh를 한 번 교환하고 원래 요청을 한 번만 다시 보낸다. 동시 401은 하나의 갱신 Promise를 공유한다. 늦게 도착한 이전 access의 401은 이미 갱신된 토큰을 사용한다.
- 갱신 실패 또는 재시도 후 401이면 두 토큰을 지우고 로그인 화면으로 이동한다. 일반 요청의 403은 권한 오류로 반환하며 갱신하지 않는다.
- 로그아웃은 로컬 상태를 즉시 지우고, 캡처한 이전 토큰으로 서버의 refresh 폐기를 요청한다. access가 만료됐으면 refresh를 저장하지 않고 교환한 뒤 폐기한다. 서버 폐기를 확인하지 못하면 로그인 화면에 안내한다.
- 로그인과 로그아웃은 세션 세대를 변경한다. 이전 세대의 요청, 로그인 응답, refresh 응답은 새 세션을 변경하지 못한다. 로그아웃 도중 발급된 늦은 refresh도 가능한 한 서버에서 폐기한다.
- React StrictMode에서 첫 effect가 정리되어도 그 요청의 실패가 두 번째 effect의 인증 상태를 지우지 않는다.

## 유지한 정책과 한계

- Bearer 방식이며 쿠키 인증으로 전환하지 않는다. 토큰은 JavaScript에서 접근 가능하므로 XSS 방어는 별도로 필요하다. 다른 탭의 세션까지 로그아웃하지는 않는다.
- 로그아웃은 서버의 refresh를 폐기한다. 이미 발급된 access의 즉시 무효화 여부는 서버 정책을 따른다.
- `VITE_AUTH_MODE=disabled`는 기존 개발용 로그인 우회를 유지한다. 서버 `AUTH_ENABLED`와는 별개다.
- mock 로그인도 회전 가능한 refresh를 발급한다. 토큰별 계정 ID와 만료 시각을 탭의 sessionStorage에 보존해 새로고침 후에도 로그인 상태를 유지한다. mock 계정의 비밀번호는 복사하지 않는다. 기존 고정 fixture access는 화면 테스트용으로 유지한다.
- JSON 요청을 사용하는 기존 domain API에 자동 재시도를 적용한다. 스트리밍 업로드 등 재사용 불가능한 body를 추가할 때는 별도 정책이 필요하다.

## 검증

- `npm test`: 일반 자동 테스트. 실제 서버 테스트는 자격증명과 서버 URL이 없는 기본 실행에서는 건너뛴다.
- 실제 서버 테스트: `AUTH_QA_USERNAME`, `AUTH_QA_PASSWORD`, `VITE_API_BASE_URL`을 안전하게 설정한 별도 QA 환경에서 `npm test -- src/lib/auth/session.real.test.ts`를 실행한다. 운영 서버를 대상으로 실행하지 않는다. 테스트는 access 수명이 짧은 QA 서버를 전제로 하며 로그인, 실제 만료 후 동시 갱신, 로그아웃 후 refresh 거부, 잘못된 토큰의 정리를 확인한다.
- 서버는 만료 판정 시 시계 오차 60초를 허용하므로 실제 만료 테스트는 JWT의 exp 이후 62초를 더 기다린다. 120초 제한 안에 끝나도록 QA access 수명은 10초로 설정한다. 테스트 코드는 토큰과 비밀번호를 출력하지 않는다.

## C4 통합 범위와 실제 계약

- `61c9425`의 기존 인증 패치를 `6ff2ff6` develop 기준에 적용했다. 상단 바 제거, 사이드바 로그아웃, 화이트리스트, 감사 로그, YARA, PC 현황, 블랙리스트와 백엔드 구현을 유지한다. C2 복원, C3 우회 정책 작업은 이 트리에 합치지 않는다.
- 공통 client의 충돌은 자동 갱신, 세션 세대 확인과 기존 FormData 처리를 함께 보존해 해결했다. 문자열 본문에만 JSON Content-Type을 붙이고 FormData의 multipart 경계는 브라우저가 설정한다. 401 후에도 같은 FormData를 한 번만 재전송하는 회귀 테스트를 추가했다. 재사용 불가능한 스트림은 별도 정책이 필요하다.
- `docs/auth-policy.md`, `EnrollmentTokenController`, 요청/응답 DTO, `IssueEnrollmentTokenUseCase`를 기준으로 등록 토큰 계약을 맞췄다. 기존 페이지, 사이드바, mock의 ADMIN 제한은 현재 서버 정책과 충돌하므로 제거했다. 로그인한 관리자 모두 발급할 수 있으며 역할 세분화는 미래 범위다. 인증 자체는 공통 보호 라우트와 API가 담당한다.
- `POST /api/admin/agents/enrollment-tokens`: 선택 본문 `{expiresInMinutes?: number}`, 생략 또는 null이면 기본 60분, 1~1440분 정수 허용, 성공 201 `{token: string, expiresAt: string}`. 이전 mock 기본 30분, 상한 미검사, `INVALID_EXPIRY`는 각각 60분, 1440분 상한, `INVALID_INPUT`으로 수정했다. UI도 같은 범위를 요청 전에 확인한다. mock 발급 난수는 HTTPS 전용 randomUUID 대신 getRandomValues로 만들어 사설 HTTP 개발 주소도 지원한다.
- 발급은 ref 잠금으로 같은 렌더의 중복 제출을 막는다. 화면 정리 이후 발급 응답, 이전 발급의 늦은 복사 결과는 무시한다. 실패 시 입력과 오류를 유지하고 재시도할 수 있다. 원문은 발급 응답을 받은 화면에서만 보여 주고 별도 저장하거나 로그로 출력하지 않는다. 새 발급을 시작하면 이전 원문을 지운다.
- 도메인 함수의 반환형, mock 성공 응답에 실제 DTO의 타입을 명시했다. 기존 Agent 목록 테스트는 tokenStore 전체 대체 대신 부분 mock으로 세션 세대 함수를 유지한다.

## C4 기능 트리 기준 검증 결과 (경합 수정 전 baseline)

아래 145개 통과는 C4 기능 트리의 기존 기준선이며 C2/C3 합본의 최신 결과가 아니다. 합본의 최신 검증 근거는 합본 트리의 `docs/remaining-integration.md`에서 별도로 관리한다.

- `npm ci`, lint, production build, 작업 트리와 index의 `git diff --check` 통과.
- 전체 자동 테스트: 23개 파일 통과, 실제 서버 전용 1개 파일 건너뜀. 테스트 145개 통과, 실제 서버 전용 1개 건너뜀, 총 146개. 등록 토큰 mounted 테스트 9개, handler/domain 계약 테스트 13개를 포함한다. 실패하는 테스트를 먼저 실행해 역할 제한, 중복 발급, 유효 시간 상한 오류를 확인한 뒤 수정했다.
- 로그인, refresh 회전과 동시 요청, 로그아웃, `/me` readback 세대 확인, mock 탭 재로딩 회귀 테스트를 유지했다. 이는 자동 테스트 결과이며 이번 트리에서 실제 로그인 폼, 실서버 인증, 브라우저 검증을 실행한 결과가 아니다. 해당 QA는 별도 승인된 환경에서 부모 작업이 수행한다. 과거 인증 트리의 실서버/브라우저 결과를 이번 통합의 PASS로 전용하지 않는다.
- 변경된 TS/TSX 24개 경로 범위의 패턴 점검: dangerous HTML/eval 0건, console 로그 0건, localStorage 0건. auth 우회 관련 8개 행은 기존 개발용 `authDisabled` 경로와 로그인/로그아웃용 refresh의 `auth: false`이며 등록 토큰 인증을 끄지 않는다. sessionStorage 관련 22개 행은 탭 단위 인증 저장소, mock 세션과 해당 테스트다. 패턴 점검은 정식 보안 감사가 아니다.
- `npm audit`는 기존 lockfile 의존성에서 high 2건(`source-map-js`, `vite`)을 보고했다. 인증 기능 범위를 넘어서는 의존성 변경이나 `npm audit fix`는 실행하지 않았다. JS 접근 가능 토큰의 XSS 위험, 서버 access 즉시 무효화 미지원, mock과 실서버의 차이는 여전히 남는다.
- 백엔드, 보호된 `mocks/data/auth.ts`, README, 자격증명 파일은 변경하지 않았다. 다른 worktree, refs, stash, Kanban과 실행 중인 서비스를 조작하지 않았고 커밋, 푸시, PR도 만들지 않았다.
