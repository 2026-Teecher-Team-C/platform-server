# CLAUDE.md

프록시 기반 악성코드 다운로드 탐지 플랫폼의 서버 레포. 설계 근거는 설계 레포
(2026-Teecher-Team-C/project)에 있다 — 구조를 바꾸기 전에 그쪽 문서를 먼저 확인한다.

## 명령

```bash
make dev | make test | make proto | make down     # 전부 Docker 안에서 실행
```

컴포넌트별: `make test-api` · `make test-engine` · `make test-console`

## 되돌리면 안 되는 결정

- **fail-close 고정.** 검사 서버에 닿지 못하면 다운로드는 차단된다. fail-open, 정책 토글,
  재시도·서킷 브레이커·로컬 캐시 폴백을 만들지 않는다
- **탐지 엔진은 API 서버하고만 통신한다.** `engine-net`(internal)에만 붙어 있고 인터넷·AWS·Slack에
  닿지 않는다. Slack 알림·S3 업로드·DB 기록은 전부 API 서버가 한다. 엔진에 외부 연결을 추가하지 않는다
- **파일 내용 파싱은 탐지 엔진에서만.** API 서버는 받은 바이트를 해석하지 않고 엔진에 넘긴다
- **화이트리스트는 SHA256만.** TLSH는 블랙리스트에만 예약된 값이며 현재 어떤 단계도 계산하지 않는다
- **블룸 필터는 삭제가 불가능하다.** 오탐 복원은 필터를 고치지 않고 화이트리스트(조회 사슬 ①)로 막는다
- **격리 S3 객체**: 키는 `quarantine/YYYY/MM/DD/{uuid}`(확장자 없음), `Content-Type: application/octet-stream`,
  버킷은 Block Public Access. 원본 파일명은 `quarantine_files.original_filename`에만 둔다
- 비대응 범위(QUIC, 피닝 앱, Range 분할, USB, 제로데이, 행위 분석)는 "고치지" 않는다

## api-server (DDD)

- 바운디드 컨텍스트 = `com.teecherteamc.platform.<context>` 패키지. 각 `package-info.java`에 소유 테이블과
  애그리거트 루트가 적혀 있다
- 컨텍스트 안은 `domain` / `application` / `infrastructure` / `interfaces`. `domain`은 Spring·JPA 외의
  프레임워크에 의존하지 않는다. 다른 컨텍스트의 애그리거트를 직접 수정하지 않고 도메인 이벤트로 알린다
- `lookup`은 예외다. 다운로드마다 호출되는 읽기 경로라 애그리거트를 로딩하지 않고 Redis·전용 쿼리로 읽는다
- 스키마는 Flyway가 소유한다(`ddl-auto: validate`). 기존 마이그레이션 파일은 수정하지 않고 `V{n}__...`를 추가한다
- 코드 스타일: 우테코 스타일(4칸, 줄바꿈 8칸, 120자). 테스트 메서드 이름은 한글 가능

## 관리 REST API 공통 규칙 (2026-10-18 팀 결정)

- **목록 응답은 `PageResponse`**(`common/PageResponse`: `content`·`page`(0부터)·`size`·`totalElements`·`totalPages`).
  쿼리는 `page`·`size`. 커서 방식(`items`·`nextCursor`)은 폐기했다 — 다시 만들지 않는다
- **에러 응답은 공통 `ErrorResponse`.** interfaces 계층에서 `ApiException(ErrorCode.X, 메시지)`를 던지고
  `GlobalExceptionHandler`가 바꾼다. 컨트롤러가 자체 에러 바디를 만들지 않는다. 상세는 `docs/api-convention.md`
- **인증은 세션 쿠키.** Bearer 토큰을 쓰지 않는다. 콘솔(Vercel)과 API(EC2)가 다른 사이트라 운영 쿠키는
  `SameSite=None; Secure; HttpOnly`, CORS는 정확한 출처 + `allowCredentials`, 콘솔은 `credentials: 'include'`.
  SSE도 같은 쿠키로 인증한다. 상세는 `docs/auth-policy.md`
- **API를 바꾸는 사람이 콘솔 `console/src/lib/api/*.ts` 타입과 mock 핸들러도 같은 PR에서 고친다.**
  서버와 콘솔 응답 모양이 어긋난 채로 머지하지 않는다
- **관리자 쓰기 작업은 감사 로그를 남긴다.** `AuditLogRecorder`로 그 작업과 같은 트랜잭션에서 기록하고,
  비밀번호·토큰 원문은 `detail`에 넣지 않는다

## proto

- 원본은 `proto/`. 에이전트 레포가 `proto-v*` 태그로 고정해 쓴다
- 필드 번호 재사용·타입 변경·삭제 금지. 바꾸면 태그를 새로 단다
