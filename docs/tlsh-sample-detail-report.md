# TLSH 기준 샘플 상세 및 초록색 체크 후속 구현 보고

## 결과와 범위

- 작업 트리: `/srv/project/T-teamc/platform-server-console-tlsh-evidence`, 브랜치 `feat/console-tlsh-evidence`, HEAD `9d6556d053158e5d9ae2f2d4fde25cfe7d7426f4` 유지. 커밋, push, PR, 카드 상태 변경 없음.
- 이벤트의 최근접 기준 샘플 SHA-256을 클릭하면 `/samples/:sha256`에서 **현재 저장된 메타데이터**를 읽는다. 원래 이벤트로 돌아가는 링크도 제공한다.
- 실제 `GET /api/admin/samples/{sha256}`를 추가했다. 기존 인증 정책을 적용하며 새 공개 예외는 없다. ADMIN과 VIEWER 조회, 익명/잘못된 토큰 거부를 PostgreSQL REST 통합 테스트로 확인했다.
- SHA-256 원문이 정확히 64자리 hex인지 먼저 검증한다. 공백을 trim해 잘못된 입력을 고치지 않는다. 유효한 대문자는 검증 후 소문자로 조회한다. 잘못된 형식은 400, 현재 행이 전혀 없으면 404다.
- `hash_blacklist`와 `file_fingerprints`를 단일 parameterized SQL로 조회한다. 한쪽만 존재하면 다른 쪽은 null이며, 비활성/계산 불가/기록 없음/현재 행 없음은 서로 구분한다. migration, 쓰기 API, TLSH 판정 정책 변경 없음.
- 표시 필드: SHA-256, TLSH, 블랙리스트 사유/출처/심각도/활성 상태/등록 시각, 지문 계산 상태/알고리즘 버전/출처/출처 설명/기록 시각. 없는 파일명, 파일 크기, 외부 공급자 출처, 다운로드 URL을 만들지 않는다.
- `NONE && distance !== null && distance > 70`만 초록색 ✓ 및 `TLSH 추가 조치 없음` 접근성 이름/툴팁으로 표시한다. **정확히 70은 WARN, 황색 ! 유지**, 0/30은 적색 ×, NONE null/UNAVAILABLE/평가 없음은 회색 ? 유지.
- 제목에는 아이콘만 붙이고 추가 상태 문구는 붙이지 않았다. 기존 접힌 설명과 내부 가이드를 보존하고 70 초과 범례만 초록색 ✓로 바꿨다. 초록색 체크는 파일 안전성의 증거가 아니라 추가 TLSH 조치가 없다는 뜻임을 가이드에 명시했다. 최종 판정, 설명, 수치는 유지했다.

## 이번 후속 작업의 파일

기존 dirty 상태를 HEAD 변경 전체와 혼동하지 않도록 시작 manifest와 비교했다. 아래가 이번 후속 작업 범위다.

신규:
- `api-server/src/main/java/com/teecherteamc/platform/sample/domain/{FindSamplePort,SampleDetail}.java`
- `api-server/src/main/java/com/teecherteamc/platform/sample/infrastructure/FindSamplePortImpl.java`
- `api-server/src/main/java/com/teecherteamc/platform/sample/interfaces/{SampleController,SampleDetailResponse}.java`
- `api-server/src/test/java/com/teecherteamc/platform/{SampleControllerTest,SampleDetailRestIntegrationTest}.java`
- `console/src/lib/api/samples.ts`
- `console/src/pages/{SampleDetailPage.tsx,SampleDetailPage.test.tsx}`
- `console/src/mocks/handlers/{samples.ts,samples.test.ts}`
- `console/src/components/events/SimilarityStatus.test.tsx`
- 이 보고서

수정:
- `console/src/app/router.tsx`, `console/src/mocks/handlers/index.ts`: 보호된 상세 경로와 MSW handler 연결
- `console/src/pages/EventDetailPage.tsx`, `EventDetailPage.interaction.test.tsx`: 정확한 SHA 링크와 이벤트 복귀 경로
- `console/src/components/events/SimilarityEvidence.tsx`, 관련 기존 test/interaction test: 링크, 아이콘, 범례 및 Router context
- `console/src/index.css`: scoped `.event-similarity-icon--success` 한 규칙 추가
- `api-server/src/test/java/com/teecherteamc/platform/DownloadEventDetailRestIntegrationTest.java`: 변경된 현재 지문을 새 API로 읽어도 과거 이벤트 근거는 유지됨을 확인하는 단언 추가

시작 manifest에 있던 나머지 16개 파일은 SHA-256이 그대로다. 기존 이벤트 API production query/DTO, 목록/대시보드, 기존 fixture, 기존 보고서 등은 이번 작업에서 바꾸지 않았다. 전체 경로와 해시는 ignored 검증 결과를 참고한다.

## TDD와 자동 검증

실제 실행 로그는 `api-server/build/tlsh-sample-detail/`에 보관했다.

| 수직 기능 단위 | RED 실행 결과 | GREEN 실행 결과 |
| --- | --- | --- |
| 거리별 아이콘/접근성/안전성 안내 | `greencheck-red.log`: 8 실패 | `greencheck-green.log`: 8 통과 |
| PostgreSQL 현재 샘플 조회 | `api-red.log`: 새 GET의 200 단언 실패 | `api-green.log`: 1 통과 |
| 이벤트 → 샘플 링크 | `link-red.log`: 링크 없음으로 1 실패 | `link-green.log`: 관련 25 통과 |
| 실제 router의 샘플 경로/현재 필드 | `page-red.log`: 404 페이지로 1 실패 | `page-green.log`: 1 통과 |
| null 기록을 비활성으로 오인하지 않기 | `null-red.log`: 1 실패 | `null-green.log`: 2 통과 |
| 형식/404/403/오류 재시도/늦은 응답/안전한 복귀 | `states-red.log`: 15 실패, 미처리 응답 4건 | `states-green.log`: 17 통과, 미처리 응답 없음 |
| MSW authoritative 계약 연결 | `msw-red.log`: 2 실패 | `msw-green.log`: 2 통과 |

기존 정책으로 이미 구현된 인증, 원문 검증 선행, 비활성/계산 불가, 현재 지문 변경과 과거 근거 불변도 회귀 테스트로 추가했다. 이 보강 테스트의 최초 실행 성공을 RED로 주장하지 않는다. 마지막 개행을 포함한 SHA 원문 거부도 최초 실행부터 통과한 회귀 검사로 추가했으며 `literal-newline-regression.log`에 남겼다.

최종 전체 실행:
- Console: `npm run lint` 성공, `npm test -- --reporter=json --outputFile=../api-server/build/tlsh-sample-detail/console-tests.json` **245/245 통과**, `npm run build` 성공. 직전 215개 대비 신규 30개이며 전체 테스트 수와 구분한다.
- API: `./gradlew test check bootJar --rerun-tasks` **466/466 통과, 실패/오류/skip 0**. 15 actionable tasks 모두 executed로 새로 실행했다. 신규 REST 4개 + controller unit 1개, 기존 event integration 단언 보강 포함. 직전 461개와 구분한다.
- API REST 검증: 익명 401, 잘못된 Bearer 401, ADMIN 및 VIEWER 200, literal 형식 오류 400, 유효하지만 없는 SHA 404, 대문자 조회, blacklist-only/비활성, fingerprint-only/LOW_ENTROPY/null TLSH, 필드/시각 및 원문 검증 전 lookup 미호출.
- `git diff --check` 성공. 후속 변경 경로의 정적 위험 패턴 검사 결과 0건. 독립 리뷰는 부모 담당이며 이 보고서는 독립 리뷰 PASS를 주장하지 않는다.

## 실제 서버와 UI 검증

기존 private Vite preview를 유지하고 새 bootJar로 **소유한 QA backend만** 교체했다.

- 실제 UI: `http://100.70.241.6:5187`
- WARN 31 클릭 시작: `http://100.70.241.6:5187/events/2ababdf5-ac17-47b3-896c-806fa620c81c`
- 샘플: `http://100.70.241.6:5187/samples/86b8d2587beb35cbba57d40cad4b976eb75e6962279f64c6bb9f353e8c31af3a?returnTo=%2Fevents%2F2ababdf5-ac17-47b3-896c-806fa620c81c`
- NONE 71: `http://100.70.241.6:5187/events/e77cc8a4-a63c-4270-b3e1-565076c12e67`
- WARN 70: `http://100.70.241.6:5187/events/be9d20a9-b2f5-447c-bf13-0032e8088a97`
- 거리 없음: `http://100.70.241.6:5187/events/349eb26a-e5b3-485d-8a34-3063a9411110`
- 부모 재기동 후 현재 backend process handle **`proc_013a87aa6c6c`**, PID **167281**. REST `127.0.0.1:18086`, gRPC `127.0.0.1:19086`을 사용한다. 이전 자식의 `proc_0ea344ebe1d3`는 인계되지 않아 종료됐으며 부모가 재기동하고 실제 샘플 GET 200을 확인했다. private preview handle은 기존 **`proc_65f3d9644128`**, private bind 유지. 별도 localhost preview/5186 mock/다른 서비스는 중지하지 않았다.
- QA preview는 `VITE_API_MODE=real`, 로컬 인증 우회 설정이다. 정상 인증 정책 검증은 auth-enabled PostgreSQL REST 자동 테스트로 별도 수행했다. 브라우저 로그인/토큰 만료 흐름의 수동 E2E를 주장하지 않는다.

### 실제 readback

1. 지정 기준 SHA는 작업 전 현재 blacklist/fingerprint 행이 없고 이벤트 당시 근거만 있었다. 허가된 **`teamc-tlsh-pr3-qa-postgres` / `tlshqa`**에만 현재 두 행을 transaction으로 추가했다.
2. 실제 API readback에서 source `MANUAL`, severity `HIGH`, 활성 true, fingerprint provenance `ADMIN_IMPORT`, algorithm `synthetic-review-assigned-v1`, 저장 시각 및 명확한 synthetic 설명을 확인했다. TLSH는 기존 평가에 있던 할당값 `T1` + `A` 70개를 사용했다. 바이트에서 계산한 실제 지문이나 외부 악성 샘플로 주장하지 않는다. 사유/출처 설명에도 이 사실을 남겼다.
3. seed 전/후 실제 GET한 기존 이벤트 **8개 전체 응답이 동일**했다. 새 API는 현재 메타데이터, 이벤트 API는 과거 FK 스냅샷을 읽는다.
4. 실제 브라우저에서 WARN 샘플 SHA 클릭 → 현재 상세 API 200/필드 표시 → 돌아가기 → 원래 이벤트, 최초/복귀 이벤트 응답 동일을 확인했다. 실제 fetch URL/status/body를 저장했고 controlling service worker 없음도 확인했다.
5. 1440px desktop 및 390px mobile 각각 8개 상태, 총 **16개 경계 화면**을 실제 API로 조회했다. 0/30 적색 × `rgb(180,35,24)`, 31/70 황색 ! `rgb(147,55,13)`, 71 초록색 ✓ `rgb(21,128,61)`, NONE null/UNAVAILABLE/평가 없음 회색 ? `rgb(102,112,133)` 확인. 제목 아이콘 aria-label/title, 설명 기본 접힘, 최종 판정 유지, 수평 overflow 없음, 정상 경계 조회 JS 오류 없음 확인.
6. desktop/mobile 샘플 상세의 긴 SHA/TLSH/출처 문구 줄바꿈과 overflow 없음 확인. 열린 내부 가이드도 두 크기에서 초록색 ✓, 안전성 비보장 안내, 제목 옆 문구 없음 확인.
7. 실제 없는 SHA의 404 안내/재시도 없음, 브라우저에서 요청을 의도적으로 차단한 실패 화면 → 차단 해제 → 재시도 → 실제 200 복구 확인. 이는 서버 장애 관측이 아니라 fault injection이다.
8. 코딩 중 임시 JSX 오류는 수정 완료했다. 최종 `/src/pages/SampleDetailPage.tsx`와 `/src/components/events/SimilarityEvidence.tsx` **실제 served module HTTP 200**, production build 및 살아 있는 backend health 200 재확인. SPA HTML 200만으로 판정하지 않았다.

### 보관 증거와 한계

- ignored `api-server/build/tlsh-sample-detail/`: RED/GREEN/full-check 로그, console JSON, `verification-summary.json`, `seed-readback.json`, seed 전/후 이벤트 JSON, `browser-clickthrough.json`, desktop/mobile boundary JSON, sample-mobile JSON, missing/retry JSON, expanded-guide JSON, desktop/mobile 샘플/71 스크린샷.
- 계정/DB 비밀번호/JWT secret은 보고서나 저장 응답에 포함하지 않았다. 기존 scratch launcher는 secret file/실행 시 생성값을 사용한다.
- 실제 PostgreSQL → REST → browser 조회/이동 범위 검증이다. 실제 악성 파일 다운로드, MalwareBazaar 동기화, 엔진 실행, 파일 bytes↔TLSH 일치 검증, object storage 검증은 수행하지 않았다. 다운로드/쓰기 기능도 제공하지 않는다.
- 공개 이벤트 목록 예외는 기존 정책이며 변경하지 않았다. 신규 샘플 경로는 기존 authenticated 경계 안에 있다.
- 부모의 독립 리뷰와 사용자 커밋 승인은 남겨 두었다. 프로세스와 데이터는 검토를 위해 계속 실행/보존한다.

## 제안 커밋 메시지 — 실행하지 않음

```text
feat: 유사도 기준 샘플 상세를 열고 추가 조치 없음을 구분한다

- 이벤트의 기준 샘플 해시에서 현재 메타데이터를 읽고 원래 이벤트로 돌아가기
- 인증된 샘플 조회 API와 누락, 오류, 늦은 응답 처리 추가
- 70 초과 NONE만 초록색 체크로 표시하고 70 경고 및 거리 없음 구분 유지
- 실제 PostgreSQL API와 데스크톱, 모바일 화면 검증 추가
```
