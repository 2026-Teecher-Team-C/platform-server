# TLSH PR3, 콘솔 근거 표시와 읽기 REST 계약

## 결과, 범위

- 작업 폴더 `/srv/project/T-teamc/platform-server-console-tlsh-evidence`, 브랜치 `feat/console-tlsh-evidence`.
- 승인된 로컬 PR2 `9d6556d053158e5d9ae2f2d4fde25cfe7d7426f4` 위의 미커밋 변경이다. 명시적 develop fetch 결과 `origin/develop=f7f83d0ef830219cb88a03bdaa0745ad2b1fa6a1`, 원격 병합을 뜻하지 않는다.
- 이벤트 상세에서 다운로드 당시 TLSH 근거를 읽고, 목록에서 WARN을 찾을 수 있다. 최종 판정, 출처, 페이지/필터, 기존 요청 수명 관리는 유지한다.
- API 전체 **461개, 실패 0, 오류 0, skip 0**, 콘솔 전체 **208개, 실패 0, skip 0**, lint와 production build 통과. API는 이전 455개에 6개를 추가했다. 콘솔 새 테스트 13개다.
- **실제 API → 브라우저 결합 QA 완료(2026-11-09 KST 후속 검증).** 사용자 재승인 뒤 격리 PostgreSQL/Redis, 현재 bootJar, real-mode 콘솔을 연결해 수동 QA 53/53개를 확인했다. 최초 DB/Redis 생성 명령의 승인 대기 시간 초과와 당시 미완료 상태는 아래 원본 기록으로 보존하며, 새 결과와 한계는 마지막 후속 절을 따른다.
- 독립 리뷰는 부모 에이전트에게 남아 있다. 커밋, push, PR, merge, deploy, Kanban 변경 없음. 다른 worktree, TLSH core, 인증 정책, 격리 정책, vendor, V1~V9 migration, proto, agent, engine은 수정하지 않았다.

## 실제 REST 계약

### 상세 `GET /api/admin/download-events/{eventId}`

기존 필드를 유지하고 아래 nullable 필드를 추가했다.

```json
{
  "similarity": {
    "classification": "WARN",
    "matchedSampleSha256": "7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a",
    "distance": 45,
    "assessedAt": "2026-10-11T00:00:00Z"
  }
}
```

- 바깥 `similarity=null`: 해당 이벤트에 연결된 평가 기록 없음. CheckHash-only, 과거 기록, 평가하지 않은 경우를 임의로 NONE으로 바꾸지 않는다.
- `classification`: `BLOCK | WARN | NONE | UNAVAILABLE`.
- `matchedSampleSha256`, `distance`: 실제 당시 최근접 샘플과 거리, 후보가 없거나 계산 불가이면 null. NONE에도 70 초과의 실제 최근접 샘플이 있을 수 있다.
- `assessedAt`: 평가 당시 시각이다. 이벤트 생성 시각이나 현재 조회 시각으로 대신하지 않는다.
- 공개하지 않는 값: 내부 corpus token/잠금, TLSH 원문, 내부 assessment/analysis 식별자. 정책 계산을 프런트에서 다시 하지 않는다.
- 조회는 `LEFT JOIN similarity_assessments s ON s.assessment_id = e.similarity_assessment_id`다. SHA의 최신 분석, 현재 캐시, 현재 기준 지문으로 fallback하지 않는다. 미연결 행도 유지한다.
- 기존 null 가능 파일 메타데이터와 판정, cacheHit의 기존 null→false 매핑을 바꾸지 않았다. 없는 ID는 404, 잘못된 UUID는 400을 유지한다.

### 목록 `GET /api/admin/download-events`

- `similarityClassification: BLOCK | WARN | NONE | UNAVAILABLE | null`만 추가한다. 상세 전체 스냅샷을 목록에 싣지 않는다.
- 같은 이벤트 FK를 LEFT JOIN하므로 분류는 상세와 같다. 1:1 PK 조인이며 count 쿼리, 시간 경계(from 포함/to 미포함), 정렬, page/size와 필터를 바꾸지 않았다.
- **기존 인증 경계:** `SecurityConfig`는 시연용 GET 목록만 `permitAll`, 상세는 인증 필수다. 처음 추가한 테스트가 목록도 401이라고 잘못 가정해 전체 실행에서 1개 실패했다. 실제 설정을 확인한 뒤 목록 200/상세 401의 기존 계약을 검사하도록 수정했다. 인증을 약화시키거나 공개 예외를 새로 만들지 않았다. 이번 목록 분류도 기존 공개 목록 응답에 포함된다는 제한이 있다. 운영 목록 공개 해제는 별도 승인 범위다.

## 화면 해석과 데이터 흐름

- 상세는 기존 domain client → 기존 요청 상태/오래된 응답 무시 → `SimilarityEvidence`로 흐른다. 목록에서 기존 상세 링크를 그대로 사용한다.
- BLOCK: `유사도 차단`, WARN: `유사도 경고`와 경고만으로 차단된 것이 아니라는 안내.
- NONE/거리 존재: `경고 범위에 해당하는 유사 샘플 없음`, NONE/null: `비교 가능한 기준 샘플 없음`.
- UNAVAILABLE: `TLSH 계산 불가`, NULL: `기록된 유사도 평가 없음`. 계산 불가, 미연결, 일치 없음은 서로 다른 문구다.
- 거리는 백분율이 아니고 작을수록 유사하다는 설명, 유사도만으로 악성을 입증하지 않는다는 설명을 표시한다. 최종 RELEASED/BLOCKED와 실제 ENGINE/SIMILARITY 출처는 별도로 유지한다.
- 기준 SHA는 전체 텍스트로 표시하고 기존 `event-long-value`, `event-hash` 줄바꿈/선택 복사를 재사용한다. 존재하지 않는 샘플 페이지 링크나 새로운 copy 상호작용은 추가하지 않았다.
- 목록 WARN 배지는 최종 RELEASED 배지 옆의 보조 표시다. 전역 통계, 알림, 자동 소급 경고는 추가하지 않았다. Dashboard 출처 라벨에도 SIMILARITY를 추가했다.
- TypeScript는 상세의 nested object와 목록의 분류를 분리한다. MSW도 같은 JSON 구조다. 기존 1~4번 샘플의 ID, 판정을 유지하고 `similarity:null`을 명시했다. 5~9번은 BLOCK/WARN/NONE(거리120)/NONE(후보없음)/UNAVAILABLE의 합성 기록이다. 실제 검사나 외부 feed가 아니다.
- 중요한 DTO null 의미, 정확한 FK 조회, WARN 해석, 모의 데이터 출처에 한국어 설명 주석을 추가했다.

## 실제 RED → GREEN

증거는 무시되는 `api-server/build/tlsh-pr3/`와 `console/node_modules/.cache/tlsh-pr3/`에 있다. 콘솔 증거도 API build 폴더의 `frontend/`에 복사했다. 아래는 실제 실패를 확인한 뒤 대응 구현을 한 순서다.

| 동작 | RED | GREEN |
|---|---|---|
| 상세 nullable 계약 | `red-null.log`, 기존 응답에 similarity 키 없음 | `green-null-final.log` |
| 당시 FK 스냅샷, 5개 분류/거리 조합 | `red-snapshot-valid.log`, similarity가 null이라 실패 | `green-snapshot.log` |
| 목록의 당시 분류 요약 | `red-list.log`, similarityClassification 키 없음 | `green-list.log` |
| 상세 WARN와 실제 RELEASED 동시 표시 | `frontend/red-warn.log`, TLSH 블록 없음 | `frontend/green-warn.log` |
| BLOCK/NONE/UNAVAILABLE/NULL 의미 구분 | `frontend/red-states.log`, 5개 표시 기대 실패 | `frontend/green-states.log` |
| MSW 상세/목록 계약과 기존 fixture 보존 | `frontend/red-mock.log`, 분류가 undefined | `frontend/green-mock.log` |
| 목록 WARN, Dashboard SIMILARITY 출처 | `frontend/red-list-ui-valid.log`, 두 표시 누락 | `frontend/green-list-ui.log` |
| 새 모의 기록의 시각 순서 | `frontend/red-mock-time.log`, 결정 후보다 생성 시각이 과거 | 최종 frontend 전체 208개 통과 |

시행착오도 구분한다. `green-null.log`는 처음 domain row만 바꾸고 별도 REST DTO를 놓쳐 실패한 중간 실행이다. `red-snapshot.log`는 fixture의 필수 held_at 누락이라 유효 RED로 세지 않고 고친 뒤 valid 로그를 남겼다. `red-list-ui.log`는 Dashboard의 기존 한국어 최종 판정을 영문으로 기대한 테스트도 포함했으므로 valid 로그로 대체했다. 추가 오류/404/재시도/오래된 응답 회귀와 인증 유지 테스트가 처음부터 RED였다고 주장하지 않는다.

## 실행 검증

### API

```sh
cd api-server
./gradlew test --tests '*DownloadEventDetailRestIntegrationTest' --tests '*DownloadEventListRestIntegrationTest'
./gradlew test check bootJar --rerun-tasks
```

- focused 기록 `green-list.log`, 최종 전체 `full-final.log`, **15 actionable tasks, 15 executed, BUILD SUCCESSFUL**. UP-TO-DATE 테스트 재사용이 아니다.
- XML 56 suite를 프로그램으로 합산, `summary-final.json`과 `final-junit/` 보존: **461 / 0 / 0 / 0**. 상세 REST 9개, 목록 REST 13개가 포함된다.
- 새 PostgreSQL REST 회귀는 실제 서버 트리거로 event FK를 연결한다. 같은 SHA의 새 평가를 넣고 기준 지문을 추가/갱신한 뒤에도 기존 event의 nested object가 같고, 같은 SHA의 미연결 CheckHash형 이벤트는 NULL임을 확인한다. BLOCK/WARN/NONE120/NONEnull/UNAVAILABLE 각각 실행했다. 응답 object의 정확한 4개 키 비교로 내부 token 노출도 방지한다.
- 전체 suite의 gRPC는 기존 fake engine/S3 범위이며, 실제 YARA/agent/S3/파일 다운로드의 end-to-end 검증이 아니다.
- 패키지 `api-server/build/libs/api-server-0.0.1-SNAPSHOT.jar` 생성. 배포하지 않았다.

### 콘솔

```sh
cd console
npm ci
npm run lint
npm test -- --reporter=json --outputFile=node_modules/.cache/tlsh-pr3/full-final.json
npm run build
```

- 최종 **208개 통과**, 실패/skip 0. `full-final.json`을 코드로 합산했다. lint, TypeScript, Vite production build 통과.
- 새 상세 DOM 회귀는 실제 React mount로 loading, 실패→재시도, 404, 다른 이벤트로 이동한 뒤의 늦은 성공/실패 무시를 확인한다. 단순 표시 분기만 SSR로 검사한다. 목록/Dashboard도 DOM mount로 검사했다. MSW 계약 테스트는 인증을 stub하므로 인증 검증이라고 부르지 않는다.
- `npm ci` 성공, 기존 lockfile 그대로다. `npm audit`는 **high 2개**, `source-map-js`, `vite`를 보고했다. 기존 의존성/lockfile 문제이며 이 카드에서 임의 upgrade하지 않았다. 상세 advisory는 `frontend/audit.json`에 있다. 서버 노출 위험을 줄이려고 리뷰 Vite는 localhost에만 바인딩했다.
- `git diff --check` 통과. tracked/untracked 21개 소스 파일을 함께 정적 패턴 스캔, credential/shell/eval/역직렬화/SQL interpolation 일치 없음(`static-scan.json`). 독립 보안 리뷰를 대신하지 않는다.

### 브라우저, 모의 API만 검증

- 실제 Chromium에서 `/events` WARN 배지 → 해당 상세 링크 → RELEASED/ENGINE/거리45를 확인했다.
- BLOCK(`...0005`), WARN(`...0006`), 평가 없음(`...0001`) 상세를 **1280×800, 390×844의 6개 조합**으로 검사, 모두 기대 문구 표시, 문서 가로 넘침 없음. `tlsh-pr3-browser.json`, `warn-desktop.png`, `warn-mobile.png`에 최종 증거를 보존했다.
- NONE120(`...0007`), 후보 없음(`...0008`), UNAVAILABLE(`...0009`)도 실제 DOM에서 각각 구분했다(`tlsh-pr3-other-states.json`).
- 실제 window.fetch 응답을 의도적으로 실패시켜 상세 오류 안내를 확인하고, 원래 fetch로 복구한 뒤 다시 시도로 WARN을 확인했다. 실제 서버 장애가 아니라 브라우저 오류 주입이다.
- 브라우저는 저장소의 **모의 viewer session token**을 sessionStorage에 넣어 MSW 인증만 통과했다. 비밀번호를 입력하거나 실제 로그인한 것이 아니다. UI auth-disabled와 MSW handler의 토큰 요구는 별개라는 기존 동작을 유지했다.

## 남겨둔 리뷰 환경과 정확한 제약

- 실행 중 프런트: **http://127.0.0.1:5186**, `VITE_API_MODE=mock`, `VITE_AUTH_MODE=disabled`, process session `proc_c14be08fef93`. 최종 curl 200, localhost 리스너 확인. 부모에게 process 소유권을 넘긴다.
- 직접 경로: `/events`, `/events/30000000-0000-4000-8000-000000000005`(BLOCK), `...000006`(WARN), `...000001`(평가 없음).
- 새 브라우저 세션에서는 MSW viewer token이 필요하다. 개발자 콘솔에서 모의 세션만 설정한 뒤 새로고침한다: `sessionStorage.setItem('teecher.console.accessToken', 'mock-access-viewer')`. 실제 서비스 자격증명이 아니다. 외부 PC에서는 SSH localhost tunnel 또는 HTTPS preview가 필요하다.
- 처음 private HTTP 주소 `http://100.70.241.6:5186`도 열어 보았으나 `isSecureContext=false`, serviceWorker 없음으로 MSW가 동작하지 않았다. HTTP 200을 화면 검증 성공으로 세지 않았다. 이제 서버를 localhost-only로 바꿨으므로 private 주소는 제공하지 않는다. 다른 기존 서비스를 중지하지 않았다.
- **백엔드/새 QA DB는 생성하지 못했다.** 도구가 격리 컨테이너 생성 명령을 승인 대기 시간 초과로 차단했고, 같은 결과를 우회하지 않았다. 기존 DB에 데이터를 넣거나 다른 QA 서버를 TLSH 서버인 것처럼 사용하지 않았다. 새 API → UI 연결과 실제 REST 로그인 브라우저 검증은 남은 acceptance gate다.
- CheckHash protocol/eventId 변경, live MalwareBazaar/악성 다운로드/피드 동기화, 자동 retro alert, agent 수정, 기준 corpus 운영 부하 측정은 범위 밖이다.

## 변경 파일

API:
- `api-server/src/main/java/com/teecherteamc/platform/admin/interfaces/DownloadEventDetail.java`
- `api-server/src/main/java/com/teecherteamc/platform/admin/interfaces/DownloadEventListItem.java`
- `api-server/src/main/java/com/teecherteamc/platform/event/domain/DownloadEventDetailRow.java`
- `api-server/src/main/java/com/teecherteamc/platform/event/domain/DownloadEventListRow.java`
- `api-server/src/main/java/com/teecherteamc/platform/event/domain/EventSimilarity.java` (신규)
- `api-server/src/main/java/com/teecherteamc/platform/event/infrastructure/FindDownloadEventPortImpl.java`
- `api-server/src/main/java/com/teecherteamc/platform/event/infrastructure/ListDownloadEventsPortImpl.java`
- `api-server/src/test/java/com/teecherteamc/platform/DownloadEventDetailRestIntegrationTest.java`

콘솔:
- `console/src/lib/api/events.ts`, `console/src/lib/dashboard/summarize.test.ts`
- `console/src/components/events/SimilarityEvidence.tsx`, `SimilarityEvidence.test.tsx` (신규)
- `console/src/pages/EventDetailPage.tsx`, `EventsPage.tsx`, `DashboardPage.tsx`
- `console/src/pages/EventDetailPage.interaction.test.tsx`, `EventSimilarityList.test.tsx` (신규)
- `console/src/mocks/data/events.ts`, `console/src/mocks/handlers/events.ts`, `events.test.ts` (신규)
- `console/src/index.css`

문서: `docs/tlsh-pr3-report.md` (신규).

## 제안 커밋, 아직 커밋하지 않음

`feat: 다운로드 이벤트에서 TLSH 유사도 근거와 경고를 확인한다`

- 다운로드 당시 평가를 이벤트 FK로 조회하고 평가 없음과 계산 불가를 구분한다
- 목록 경고와 상세 최근접 샘플, 거리, 평가 시각을 최종 판정과 함께 표시한다
- PostgreSQL REST 계약과 모의 데이터, DOM 상태 전이, 반응형 표시를 검증한다

독립 리뷰와 실제 API-to-UI 확인 결과를 부모가 검토한 뒤, 커밋은 별도 승인을 받아야 한다.

## 후속: 사용자 재승인 후 실제 API → 브라우저 QA 완료

### 현재 결과와 출처

- 최초 승인 timeout 이후 사용자가 `테스트해봐`로 실제 실행을 재승인했다. 위의 미생성/미완료 서술은 **재승인 이전 시점의 기록**이다. 이번에는 별도 DB/Redis가 실제 running임을 확인하고 검증했다. 다른 서비스나 DB를 빌려 쓴 우회가 아니다.
- fixture 생성: `2026-11-08T15:53:32Z` (= 2026-11-09 00:53:32 KST). 최종 집계/재조회: `2026-11-08T16:01:37Z` (= 2026-11-09 01:01:37 KST).
- **실제 실행 중인 Java API가 격리 PostgreSQL에 직접 추가한 합성 메타데이터를 읽고, Chromium에서 real-mode React 콘솔로 표시하는 범위**를 확인했다. 무해한 설명 문자열로 SHA-256/바이트 길이를 계산했으나, TLSH 거리와 최종 차단 판정은 표시 검증을 위해 수동 지정했다. 실제 파일 다운로드, TLSH 계산, YARA 엔진 검사, S3 업로드로 만들어진 탐지 기록이 아니다.
- 격리 DB `tlshqa`에 V1~V9 Flyway 적용 성공 9개를 DB에서 읽었다. device/agent/verdict/analysis/assessment/event를 FK/시각 순서에 맞춰 transaction으로 넣었다. 엔진 원본 분석은 CLEAN, 추가 SIMILARITY 최종 차단은 별도 기록이다. CheckHash형 합성 행에는 분석/평가를 넣지 않았다.
- assessment가 있는 7개 event는 먼저 `event_decision_evidence`에 event UUID + agent + SHA + assessment를 예약하고 V9 INSERT trigger가 정확한 FK를 연결했다. `download_events.similarity_assessment_id`를 임의 UPDATE하거나 SHA 최신 평가로 대신하지 않았다.

### 실행 환경, 계속 실행 중

| 대상 | 정확한 주소/식별자 |
|---|---|
| 실제 콘솔 | **http://127.0.0.1:5187/events** |
| 실제 API | **http://127.0.0.1:18086** |
| gRPC | `127.0.0.1:19086` (QA에서 호출하지 않음) |
| DB | `teamc-tlsh-pr3-qa-postgres`, PostgreSQL 17, `127.0.0.1:15486`, DB/user `tlshqa` |
| Redis | `teamc-tlsh-pr3-qa-redis`, Redis 8, `127.0.0.1:16386` |
| API process | `proc_40435046db3e`, Java PID `2784989`, 부모에게 handoff 완료 |
| 콘솔 process | `proc_fe29bb396d93`, Vite Node PID `2767013`, 부모에게 handoff 완료 |
| 기존 mock | `http://127.0.0.1:5186`, 기존 process `proc_c14be08fef93`, 그대로 유지 |

- 콘솔 환경은 `VITE_API_MODE=real`, `VITE_AUTH_MODE=disabled`, `VITE_API_BASE_URL=http://127.0.0.1:18086`다. API의 `AUTH_ENABLED=false`, CORS 허용 origin은 정확히 `http://127.0.0.1:5187`이다. QA 전용 bootstrap/JWT/AWS 자격 값은 내부 environment로 생성/주입했고 본문이나 로그에 출력하지 않았다.
- 최종 `ss`에서 REST/gRPC/콘솔 모두 localhost 바인딩, DB accepting connections, Redis PONG, `/actuator/health` UP를 확인했다. 외부 PC에서는 SSH localhost tunnel 등 별도 안전한 연결이 필요하다. 이 URL은 공개 배포 주소가 아니다.
- 초기 실행에서 사용하지 않는 S3 endpoint override가 bucket 생성 startup을 유발하여 실패했다. 실제 코드 확인 후 **override를 비워 startup 생성 호출을 하지 않는 기존 설정**으로 재시작했다. S3 기능은 호출하지 않았고 AWS 실서비스나 기존 S3에 쓰지 않았다. 초기 gRPC `host` 설정은 bind 옵션이 아니어서 `spring.grpc.server.address=127.0.0.1`로 바로잡고 리스너를 재확인했다. 둘 다 scratch 실행 설정만 수정했고 제품 소스는 수정하지 않았다.

### 실제 DB fixture 직접 링크

모든 상세 링크의 앞부분은 `http://127.0.0.1:5187/events/`다. 전체 manifest에는 SHA, assessment/analysis ID, 정확한 assessedAt도 있다.

| 합성 상태 | eventId | 실제 API 판정/출처 | 거리 |
|---|---|---|---|
| BLOCK 경계 | `82bbb8bf-57fd-4cf6-9c00-0218484fe759` | BLOCKED / SIMILARITY | 30 |
| WARN 하한 | `2ababdf5-ac17-47b3-896c-806fa620c81c` | RELEASED / ENGINE | 31 |
| WARN 상한 | `be9d20a9-b2f5-447c-bf13-0032e8088a97` | RELEASED / ENGINE | 70 |
| NONE 먼 후보 | `e77cc8a4-a63c-4270-b3e1-565076c12e67` | RELEASED / ENGINE | 71 |
| NONE 후보 없음 | `349eb26a-e5b3-485d-8a34-3063a9411110` | RELEASED / ENGINE | JSON null |
| UNAVAILABLE | `7edda32a-fdb4-440f-8ba6-2c5c81fdad7f` | RELEASED / ENGINE | JSON null |
| CheckHash형, 평가 자체 없음 | `e3287cd7-8469-4622-bf09-48dd43edfb2f` | RELEASED / CACHE | similarity 자체 JSON null |
| BLOCK 숫자 0 표시 | `3d0cfea0-5758-4b34-b3c7-4faba79e8935` | BLOCKED / SIMILARITY | **0** |

- PC `TLSH-REAL-QA-SYNTHETIC`, Agent ID `81368252-a1de-46dd-8ce6-61263cdd1af5`.
- 최근접 샘플 SHA-256: `86b8d2587beb35cbba57d40cad4b976eb75e6962279f64c6bb9f353e8c31af3a`.
- WARN31 평가 시각: API `2026-11-08T15:44:32.300Z`, 한국어 화면 `2026. 11. 9. 오전 12:44:32` (기존 UI의 초 단위 표시).
- 사용자는 목록에서 WARN31/70의 `TLSH 경고`와 RELEASED를 함께 확인하고 `상세 보기`로 이동하면 된다. 상세의 ENGINE, 거리, 최근접 SHA, 평가 시각을 확인한다. BLOCK30/0 상세는 SIMILARITY와 BLOCKED를 유지한다. NONE 후보 유무, 계산 불가, 평가 없음은 각각 다른 안내다. 읽기 전용이라 샘플을 소비하지 않는다.

### 검증 53/53, 수동 QA 체크 집계

| 증거 JSON | 실행한 named check | 결과 |
|---|---:|---:|
| `api-checks.json` | 30 | 30 PASS |
| `browser-desktop.json` | 8 | 8 PASS |
| `browser-mobile.json` | 8 | 8 PASS |
| `browser-errors-retry.json` | 4 | 4 PASS |
| `browser-list.json` | 3 | 3 PASS |
| **합계** | **53** | **53 PASS** |

- 이 수는 기존 JUnit 461/Vitest 208과 다른 **이번 수동/스크립트 QA의 named assertion 수**다. JSON을 프로그램으로 합산한 `summary.json`에 근거한다. 이번에는 제품 코드 수정이 없어 기존 전체 suite/lint/build를 재실행하지 않았고 이전 자동 테스트 결과를 새 실행처럼 세지 않았다.
- API: 목록 총 8개와 각 event identity/SHA/최종 판정/실제 출처, 목록 분류, 상세 4개 공개 키/정확한 값, JSON null, 평가 시각을 검사했다. CORS 응답도 정확한 콘솔 origin을 확인했다.
- WARN31과 같은 SHA에 더 최신 UNAVAILABLE 평가를 넣은 뒤, 기존 이벤트 상세 전체 응답이 이전과 같음을 재조회했다. SQL도 event의 기존 FK와 새 평가 행을 각각 읽었다. 브라우저에서도 그 뒤 WARN31의 기존 거리/샘플/시각을 그대로 확인했다. 기준 fingerprint 변경까지 이 후속 QA에서 실행했다고 주장하지 않는다.
- 실제 API의 존재하지 않는 UUID `00000000-0000-4000-8000-000000000000`는 404/NOT_FOUND, `not-a-uuid`는 400/INVALID_INPUT이었다. 화면은 각각 없는 이벤트 안내와 기존 재시도 가능한 요청 실패 안내로 다르게 처리했다.
- 브라우저: 8상태 × 1280×800/390×844에서 실제 응답을 읽고 예상 문구/숫자(0 포함)/샘플, 최종 판정/출처, 문서 가로 넘침 없음을 검사했다. 목록도 두 크기로 확인했다. 목록 테이블 내부의 기존 가로 스크롤은 허용하며 문서 전체 overflow와 구분한다.
- WARN 목록 배지→실제 상세 링크 이동, 거리31/최근접 SHA/평가 시각 확인. 브라우저 fetch의 response URL은 `http://127.0.0.1:18086/api/admin/download-events/...`, 실제 JSON identity/분류가 DB fixture와 일치했다. 해당 새 origin에 controlling service worker가 없고 MSW가 응답한 것이 아님을 확인했다.
- 브라우저에서 WARN31 상세 fetch만 의도적으로 실패시켜 오류/다시 시도를 확인하고, 원래 fetch로 복구한 뒤 재시도로 실제 API 200과 WARN31 표시를 확인했다. 실제 서버 장애가 아니라 의도적인 fault injection이다. 복구된 화면과 backend/frontend를 그대로 남겼다.
- 최종 실제 API 8개를 다시 읽어 최초 값과 모두 같음을 확인했다(`fresh-api-readbacks.json`). 문서를 제외한 기존 tracked/untracked 파일의 SHA-256 manifest가 모두 같아 **이번 후속 QA 제품 소스 변경 0개**다. `git diff --check`도 통과했다.

### 증거 위치와 한계

- 무시되는 `api-server/build/tlsh-pr3/real-qa/`에 `fixtures.json`, `seed.sql`, `api-readbacks.json`, `api-checks.json`, `fresh-api-readbacks.json`, browser JSON 5종(상세 desktop/mobile, 오류/재시도, 목록, 최초 WARN 이동), `summary.json`, `source-before.json`, `runtime-readiness.log`를 저장했다.
- 화면: `events-desktop.png`, `warn-decision-desktop.png`, `warn-desktop.png`, `warn-mobile.png`. 초기 일부 screenshot 도구 timeout은 짧은 별도 캡처로 재시도해 복구했고 실제 화면을 확인했다. DOM/네트워크 체크나 서버 실패로 오인하지 않는다.
- scratch 실행 스크립트: `/home/hajh1113/.hermes/profiles/team-c/cache/scratch/tlsh-real-qa/{start-backend.py,seed.py,verify-api.py,finalize-evidence.py}`. DB secret 파일은 부모가 만든 chmod600 파일을 내부에서만 읽는다.
- 미검증: 실제 로그인/Bearer/refresh/만료, real agent/CheckHash/SubmitFile/ReportEvent 실행, 실제 엔진/TLSH 계산/정책 결정 생성, MalwareBazaar/악성 파일/외부 feed, S3/격리 동작, 동시성/운영 부하/retro alert. 이는 저장된 이벤트 읽기 화면의 실제 API 연결 QA이며 해당 흐름 전체의 E2E 성공이 아니다.
- 이번 후속 작업에서 변경한 저장소 파일은 이 보고서뿐이다. 커밋/push/PR/deploy/task state 변경 없음. 독립 리뷰 및 커밋 승인 상태는 이 QA 결과로 대신하지 않으며 부모가 별도로 관리한다. 실행 중인 검토 환경은 사용자가 볼 수 있도록 해체하지 않았다.
