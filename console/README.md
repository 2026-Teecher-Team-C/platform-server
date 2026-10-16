# console

관리 콘솔 (React + Vite + TypeScript). Vercel에 배포한다 — Vercel 프로젝트의 Root Directory를 `console/`로 둔다.

API 서버와는 REST + SSE로 통신한다. 에이전트의 gRPC 채널과는 별개다.

## 실행

레포 루트에서 전체 개발 환경을 실행한다.

```bash
make dev
```

관리 콘솔은 기본적으로 아래 주소에서 확인할 수 있다.

```text
http://localhost:5173
```

환경변수 파일은 예시 파일을 복사해서 사용한다.

```bash
cp console/.env.example console/.env
```

현재 사용 가능한 환경변수:

```env
VITE_API_MODE=real
VITE_API_BASE_URL=http://localhost:8080
VITE_AUTH_MODE=disabled
```

| 변수 | 값 | 설명 |
| --- | --- | --- |
| `VITE_API_MODE` | `real` \| `mock` | `mock`이면 Vite 개발 서버에서 MSW가 Admin REST 요청을 가로채 Notion API 명세 기반 mock 응답을 반환한다. production build에서는 mock이 활성화되지 않는다. |
| `VITE_API_BASE_URL` | URL | API 서버 주소. 비우면 `http://localhost:8080`. Vercel에서는 프로젝트 환경변수로 넣는다. |
| `VITE_AUTH_MODE` | `disabled` \| `enabled` | `disabled`면 로그인 없이 콘솔을 연다. 서버에 로그인 API가 아직 없어서 real 모드에서는 `disabled`로 둔다. 비우면 `enabled`. |

mock 화면을 개발할 때는 `VITE_API_MODE=mock`, `VITE_AUTH_MODE=enabled`로 바꾼다.

## 테스트

```bash
npm test
```

개발용 mock 계정은 아래와 같다. 비밀번호는 모두 `password`다.

| username | role |
| --- | --- |
| `admin` | ADMIN |
| `analyst` | ANALYST |
| `viewer` | VIEWER |

현재 mock으로 제공하는 주요 API:

- 인증: `POST /api/admin/auth/login`, `GET /api/admin/auth/me`
- 등록: `POST /api/admin/agents/enrollment-tokens`
- PC: `GET /api/admin/agents`, `GET /api/admin/agents/{agentId}`
- 이벤트: `GET /api/admin/download-events`, `GET /api/admin/download-events/{eventId}`
- 격리: 목록/상세/오탐 복원
- 화이트리스트: 목록/등록/사유 수정/해제
- 블랙리스트: 목록/등록/사유 수정/해제
- 정책: 바이패스 도메인 목록, 파일 타입 정책 목록
- YARA: 룰셋 목록
- 감사: `GET /api/admin/audit-logs`
- 대시보드: `GET /api/admin/stats`

목록 mock은 현재 화면 개발용 소량 데이터라 `nextCursor=null`을 반환한다. 주요 필터와 역할별 401/403은 재현한다. 쓰기 mock은 브라우저 메모리에서만 상태가 바뀌며 새로고침하면 초기 데이터로 돌아간다.

등록 토큰의 `expiresInMinutes`를 생략했을 때 mock에서는 개발 편의를 위해 30분을 사용한다. 실제 서버의 기본값/최대값은 서버 정책이 확정되면 그 계약을 따른다.

블랙리스트 등록 시 severity를 관리자 입력으로 받을지는 현재 명세에서 미확정이다. mock에서는 화면 개발을 위해 MANUAL 항목의 severity를 `HIGH` 샘플값으로 둔다. 이는 실제 API 계약이 아니다.

`/api/admin/stats` 응답 형태도 현재 명세에서 추정값으로 표시돼 있으므로 UI 개발용 임시 계약이다. 실제 통계 계약과 Prometheus 메트릭 이름은 B/C와 합의 후 확정한다.

SSE `/api/admin/events/stream`은 S3 실시간 이벤트 작업에서 별도로 mock한다.

## 검증

레포 루트에서 콘솔 lint와 production build를 한 번에 검증한다.

```bash
make test-console
```

콘솔 디렉터리에서 개별 실행도 가능하다.

```bash
npm run lint
npm run build
```
