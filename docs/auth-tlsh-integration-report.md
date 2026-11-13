# 현재 TLSH/샘플 콘솔 인증 통합 검증

## 결과와 범위

- 작업 트리: `/srv/project/T-teamc/platform-server-console-auth-tlsh-integration`, 브랜치 `verify/console-auth-tlsh`.
- HEAD `9d6556d053158e5d9ae2f2d4fde25cfe7d7426f4` 유지. 커밋, push, PR, 배포, Kanban 상태 변경 없음. 인덱스 비어 있음, 변경은 전부 unstaged/untracked.
- 기존 CURRENT TLSH 이벤트, 샘플 상세, 최근접 샘플 링크, 돌아가기, 초록 아이콘, 기본 접힌 설명과 현재 레이아웃을 보존했다. API 소스는 CURRENT 스냅샷에서 추가 변경하지 않았다.
- 이전 인증 트리의 `auth.patch`를 일반 `git apply`로 적용 가능한 경로에만 반영했다. 3-way/staging은 사용하지 않았다. 충돌한 정책 테스트는 현재 파일의 토큰 저장소 mock만 partial mock으로 고쳤고, 등록 토큰 페이지는 현재 제목/레이아웃을 유지하면서 서버 계약과 기존 경합 방어를 선택 반영했다. 인증 트리의 신규 등록 토큰 테스트 2개도 가져왔다. mock 계정/비밀번호 데이터는 복사하지 않았다.
- 독립 개발 프런트 → 독립 개발 Java API → 전용 PostgreSQL/Redis 구성만 사용했다. 공식 서버/API 및 기존 DB 쓰기는 없다.
- **미완료 게이트:** vault에 저장된 로그인 없음. 브라우저 비밀번호 입력/로그인 폼 제출은 미검증이다. 프로그램 로그인과 토큰 저장을 사용한 인증 브라우저 검증은 아래와 같이 완료했다. 부모의 독립 코드 리뷰/수동 로그인 게이트 확인 전 전체 카드 완료로 판단하지 않는다.

## 구현 연결점

- `client.ts`, `tokenStore.ts`, `session.ts`: access/refresh 쌍 저장, 세대 번호, 동시 401 단일 갱신, 한 번 재시도, 늦은 본문/갱신의 이전 계정 데이터 차단, 로그아웃 중 발급된 refresh 폐기, 폐기 실패 결과 전달.
- `AuthProvider`, 로그인/사이드바: 초기 `/me` 복원, 서버 토큰 폐기와 즉시 로컬 초기화, 이전 로그아웃 결과가 새 로그인 안내를 덮지 않음.
- MSW 세션 복원/토큰 회전/폐기 유지. reload 테스트 포함. 기존 도메인 테스트의 mock은 실제 저장소 export를 보존하는 partial mock으로 변경했다.
- 등록 토큰은 서버 `SecurityConfig`/`EnrollmentTokenController`와 동일하게 인증된 관리자 계정 역할 공통 접근. 기존 ADMIN 전용 UI 제한 제거. 1~1440분/기본 60분, 중복 제출, unmount/이전 복사 결과 경합 방어 유지.
- FormData는 원본 body를 재사용하고 multipart Content-Type은 브라우저에 맡긴다. TLSH 이벤트/샘플 domain GET도 같은 갱신을 공유하며 JSON 본문/Content-Type을 만들지 않는 테스트를 연결했다.
- 중요한 인증 경계, 세대 변경, 오류/폐기 처리는 한국어 주석으로 설명했다.

## RED → GREEN 증거

프로필 scratch: `/home/hajh1113/.hermes/profiles/team-c/cache/scratch/auth-tlsh-integration`.

1. 인증 적용 전 기존 인증 회귀 테스트를 현재 트리에서 실행: `red.log`. client 6 실패/2 통과, session 모듈 누락으로 해당 suite 로드 실패. 단일 refresh, 늦은 본문, logout 중 refresh, FormData 재시도 실패를 확인했다. 이 RED 실행은 미구현 경로 때문에 비동기 rejection 진단도 남겼다.
2. 등록 토큰 테스트 선실행: `enrollment-red.log`, 9 실패. VIEWER 공통 접근/발급 폼이 기존 ADMIN gate에 막혔다.
3. 인증 적용 후 전체 실행: 초기 277 통과/16 실패/1 skip. 현재 TLSH/샘플/정책 테스트의 전체 tokenStore mock이 신규 세대 export를 없앤 것이 원인이었다. 커버리지를 삭제하지 않고 partial mock으로 바꿔 293 통과로 복구했다.
4. 최종 테스트에는 실제 TLSH domain GET 동시 갱신 확인과 실제 서버 logout/새 로그인/늦은 refresh 응답 경합 검증을 추가했다.

## 최종 실제 실행

| 검증 | 결과 | 증거 |
|---|---|---|
| console `npm ci` | 성공 | `npm-ci.log` |
| console `npm run lint` | 성공 | `lint.log` |
| console `npm run build` | 성공 | `build.log` |
| console 기본 전체 suite | **293 통과, 0 실패, 2 skip** | `console-tests.json`, `console-tests.log` |
| console opt-in 실제 인증 suite | **2 통과, 0 실패, 0 skip** | `live-tests.json`, `live-tests.log` |
| API `./gradlew test check bootJar --rerun-tasks` | **466 테스트, 실패/오류/skip 0** | `gradle.log`, `api-counts.json`, `api-server/build/test-results/test/TEST-*.xml` |
| diff 공백 검사 | 통과 | `git diff --check` |

API JUnit XML 58개를 프로그램으로 집계했다. Gradle은 15 actionable tasks 전부 실행했다. `tlsh:test NO-SOURCE`를 테스트 수로 계산하지 않았다. 기존 Gradle deprecation 경고는 남아 있다. 첫 잘못된 루트 `./gradlew` 호출은 실행 파일이 없어 실패했으며, 실제 `api-server/`에서 위 명령을 다시 실행해 성공했다.

기본 suite의 skip 2개는 의도적인 `session.real.test.ts` opt-in 테스트다. 별도 실행은 환경변수에 scratch 계정 정보를 메모리로 주입했으며 비밀/토큰을 로그에 출력하지 않았다.

### 실제 Java API + 실제 프런트 session/domain client

- bootstrap 격리 계정으로 login → `/me` 성공.
- QA access TTL 3초, 서버 clock skew 60초를 실제로 기다린 후 동시 `/me` 요청이 401 → refresh **정확히 1회** → 모두 200. 실제 대기 포함 테스트 약 64초.
- refresh 쌍 회전, 이전 refresh 재사용 401.
- 인증된 이벤트 상세와 샘플 상세를 현재 domain client로 조회, 이벤트 matched SHA와 현재 샘플 SHA 일치.
- `endSession()` 즉시 access/refresh 로컬 제거, 서버 refresh 재사용 401.
- 유효하지 않은 refresh는 양쪽 토큰 제거.
- 별도 실제 서버 경합 테스트: 서버 Redis가 refresh를 소비한 뒤 응답 전달만 지연 → 로그아웃 → 새 로그인 → 늦은 응답 전달. 이전 요청 AbortError, 새 세션 유지, 늦은 refresh 서버 재사용 401, 정리 결과 성공.
- 본문 파싱 중 이전 계정 응답 차단, 정리 HTTP/네트워크 실패 및 새 세션 세대 경합은 결정적 unit 테스트로 추가 확인했다. 모든 경합의 실제 네트워크 변형을 브라우저에서 재현했다고 주장하지 않는다.

### 서버 인증 정책 관찰

`api-auth-boundary.json`: `/me`, 이벤트 상세, 샘플 상세는 비인증 401/인증 200. **기존 이벤트 목록 GET은 서버 정책상 비인증 200 예외**이며 이번 범위에서 변경하지 않았다. 콘솔 이벤트 라우트 자체는 인증으로 보호한다.

## 실제 브라우저 QA

- 새 origin `http://100.70.241.6:5188`는 real API, auth enabled, same-origin `/api` proxy. controlling service worker 없음.
- 비인증 `/events` → `/login`, password 입력 필드 확인.
- 프로그램 로그인에서 얻은 QA 토큰을 sessionStorage에 넣어 인증 세션 구성. 폼 비밀번호를 browser/terminal/CDP로 입력하지 않았다. `browser_vault list` 결과 빈 목록으로 폼 제출은 남겨 두었다.
- 실제 이벤트 목록에서 `none-71-SYNTHETIC.txt` 상세 클릭 → TLSH 거리 71, 초록 체크 `TLSH 추가 조치 없음`, RGB(21,128,61), 설명 기본 닫힘 확인.
- 최근접 SHA 링크 클릭 → 실제 API의 현재 샘플 상세/출처 표시 → `이벤트로 돌아가기` 클릭 → 원래 이벤트 상세와 파일명 복귀.
- 실제 만료 후 browser domain client `/me` 3개 동시 실행: 401 3개, refresh 200 **1개**, 재시도 200 3개. `browser-expiry.json`.
- 사이드바 로그아웃 클릭 → `/login`, 로컬 access/refresh 제거, 직전 refresh 서버 재사용 401. `browser-logout.json`.
- 잘못된 access/refresh fixture → 보호 라우트 진입 → `/login`, 양쪽 토큰 제거. `browser-boundaries.json`.
- `browser-event.json`, `browser-sample.json`, `browser-green.png`에 화면/의미/색상 증거 저장. 브라우저 helper의 긴 await가 5초 IPC timeout을 냈지만 브라우저 내 대기는 계속 진행되어 실제 완료된 네트워크 결과를 다음 호출에서 읽어 저장했다. 이후 새 탭 복구 후 현재 초록 아이콘 화면을 다시 확인했다.

## 격리 환경 및 데이터

- 프런트: `http://100.70.241.6:5188`, `VITE_API_MODE=real`, `VITE_AUTH_MODE=enabled`, `VITE_API_BASE_URL=''`.
- Java HTTP: `127.0.0.1:18087`, gRPC: `127.0.0.1:19087`, `AUTH_ENABLED=true`.
- PostgreSQL: `teamc-auth-tlsh-qa-postgres`, `127.0.0.1:15487`, DB/user `authtlshqa`.
- Redis: `teamc-auth-tlsh-qa-redis`, `127.0.0.1:16387`.
- bootstrap 계정 생성 완료. 계정/JWT, DB 비밀번호, 브라우저 토큰 파일은 각각 프로필 scratch에만 0600으로 보관한다. 비밀을 저장소/문서에 넣지 않았다. 수동 폼 로그인은 부모가 vault의 masked 저장 경로를 준비한 뒤 확인해야 한다.
- `api-server/build/tlsh-pr3/real-qa/fixtures.json`과 `api-server/build/tlsh-sample-detail/`에 테스트 DB 직접 삽입 데이터/응답 증거. 이벤트 8개는 무해한 문자열로 만든 SHA와 수동 지정 거리/판정/시각이다. 실제 다운로드, TLSH 계산, YARA 엔진 실행, malware feed, S3 작업 증거가 아니다.
- 현재 샘플 blacklist/fingerprint 삽입 전후 이벤트 8개 응답이 완전히 같음을 검증했다. 과거 이벤트 스냅샷을 현재 샘플 메타데이터로 덮지 않는다.
- S3 endpoint 비워 startup bucket 쓰기를 피했고 업로드/실제 AWS 호출 없음.
- 기존 5187 프런트는 여전히 listener가 있다. 기존 서비스는 중지/수정하지 않았다. 확인 시점에 18086 listener는 없었으므로 기존 리뷰 backend 상태는 부모의 별도 확인 사항이며 이 작업에서 재기동하지 않았다.

### 유지 프로세스 인계

두 프로세스 모두 `process_manage handoff` 성공 후 부모 소유로 전달했다.

- API: `proc_55119ac91dd9`.
- private Vite: `proc_d51dc0f20873`.
- 실패한 첫 Vite 실행은 npm이 잘못된 작업 디렉터리를 사용해 종료했다. 실제 유지 실행은 `npm --prefix <정확한 console 경로>`를 명시해 수정했고 private URL/listener/API proxy를 다시 확인했다.

## 보호 소스와 정적 보안 검사

- `protected-sources.json` → `protected-sources-after.json`: 원래 CURRENT 39개, 원래 auth 25개 파일 SHA-256 전부 일치, 두 트리 HEAD/status 전부 일치.
- `auth.patch`, `current.patch` 원본을 수정하지 않았다. 원래 트리, 기존 계정, 기존 DB/프리뷰 설정 변경 없음.
- `security-scan.json`: tracked 추가 줄과 untracked 파일을 함께 검사. password literal 탐지 6건은 `auth.test.ts`, `session.test.ts`, MSW `handlers/auth.test.ts`의 테스트 fixture/기존 mock 인증 테스트뿐이다. 실제 QA 계정/비밀은 아니다. unsafe shell/eval/exec/pickle 패턴 없음.
- `secret-byte-scan.json`: 생성한 실제 QA password/JWT/DB secret/access/refresh 값이 변경 저장소 파일에 없는 것을 별도로 확인했다. 보안 스캔은 독립 코드 리뷰를 대체하지 않는다.

## 부모 확인과 독립 코드 리뷰

- 부모가 새 작업 트리의 JUnit XML을 직접 합산해 466개, 실패와 오류, skip 0을 확인했다. API health, private 프런트, 같은 origin의 API proxy도 각각 HTTP 200을 확인했다.
- 부모가 보호 원본의 파일 해시와 Git 상태를 다시 대조했고 불일치가 없었다. 인덱스도 비어 있다.
- 독립 리뷰 `deleg_fe1c9fa5`, `sa-0-7e4225bb`는 CURRENT 스냅샷 대비 변경 29개 파일, 생산 코드 14개를 검토했다. 판정 PASS, 새 보안 문제와 차단할 논리 오류 없음.
- TLSH 생산 코드, API 생산 코드와 이벤트, 샘플 화면은 CURRENT와 동일하다. 기존 TLSH 테스트 3개의 차이는 새 tokenStore export를 보존하는 partial mock이다.
- 수동 비밀번호 로그인 폼 제출은 여전히 미검증이다. API 로그인과 토큰을 저장한 브라우저 검증을 폼 로그인 성공으로 대체하지 않는다. 이 게이트가 남아 있으므로 전체 카드 완료로 표시하지 않는다.
- 비차단 개선 제안은 AuthProvider 마운트 테스트 보강과 과거 인증 문서의 최신 보고서 링크다. 공식 서버 연결, 커밋, push와 배포는 하지 않았다.

## 제안 커밋 메시지 — 아직 커밋하지 않음

```text
feat: TLSH 이벤트와 샘플 화면을 로그인 세션으로 연결

- access 만료 시 한 번 갱신하고 로그아웃 시 refresh 토큰 폐기
- 현재 샘플 이동, 돌아가기와 TLSH 표시를 인증 환경에서도 유지
- 독립 개발 API에서 토큰 회전과 동시 요청, 세션 경합 검증
```
