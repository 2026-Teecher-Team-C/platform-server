# TLSH PR2 구현, 검증 보고서

## 결과와 작업 경계

- 작업 폴더: `/srv/project/T-teamc/platform-server-tlsh-similarity`, 브랜치 `feat/tlsh-similarity`.
- 기준 HEAD: 승인된 로컬 PR1 `a987a0507faed59b69620af6fa6b502828954670`. 시작 시 명시적 develop fetch 후 `origin/develop`은 `f7f83d0ef830219cb88a03bdaa0745ad2b1fa6a1`이었다. 최신 사용자 승인에 따라 원격 병합을 기다리지 않고 로컬 PR1 위에서 계속했다.
- **구현은 미커밋 상태다.** 커밋, push, PR 생성/병합, 배포, Kanban 상태 변경, 콘솔 PR3 구현은 하지 않았다. 다른 worktree는 수정하지 않았다. agent 코드는 호환성 확인을 위해 읽기만 했다.
- 최종 교정 cycle 2 후 `./gradlew test check bootJar --rerun-tasks`: **455개, 실패 0, 오류 0, skip 0, BUILD SUCCESSFUL**. 유사도 gRPC 통합 34개, Verdict 인증 통합 4개. test와 bootJar 모두 실제 새로 실행했다. 아래 442/450개 결과와 원본 로그는 이전 교정 이력으로 보존한다.
- 독립 리뷰는 부모 에이전트가 별도로 수행해야 한다. 이 보고서는 구현자 검증 결과이지 독립 승인이나 배포 승인이 아니다.

## 판정 흐름

1. 모든 업로드는 실제 SHA256 검증 후 PR1의 공식 Java TLSH 계산/저장을 거친다. 캐시, 정확 화이트리스트, YARA 차단에서도 이 단계는 유지한다. 불일치는 DATA_LOSS, 계산 불가와 DB 실패는 구분한다.
2. 직접 SubmitFile 호출도 정확 SHA 화이트리스트 → 활성 정확 블랙리스트 → 유효 캐시 순서다. TLSH로 허용하는 경로는 없다.
3. 캐시 미스에서는 기존 Python YARA RPC를 먼저 실행한다. **성공한 CLEAN만** Java 거리 비교로 넘어간다. YARA MALICIOUS/SUSPICIOUS, 구조화된 엔진 실패, RPC 장애는 비교하지 않는다.
4. 공식 고정 Java 포트 `totalDiff(..., true)`로 길이 차이를 포함한 거리를 계산한다. 활성 SHA256 블랙리스트에 실제 지문이 있고 유효 화이트리스트에 없는 샘플만 V6 뷰에서 읽는다. 최소 거리 하나만 유지하고, 동률은 SHA256 사전순으로 고정한다.
5. 거리 ≤30은 최종 MALICIOUS/BLOCK, 출처 SIMILARITY다. 31~70은 CLEAN/ALLOW를 유지하고 별도 WARN 근거를 남긴다. 70 초과는 NONE과 실제 최근접 샘플을 기록한다. SUSPICIOUS를 경고 값으로 쓰지 않는다. 후보 없음은 NONE/null, 계산 불가는 UNAVAILABLE/null이며 기존 엔진 판정을 유지한다.
6. 원본 YARA 결과는 `analyses`에 CLEAN 등 그대로 저장한다. `file_verdicts`만 최종 판정을 담으며 별도 평가가 원본 analysis를 참조한다. 유사도 차단 변종을 블랙리스트에 자동 등록하지 않는다.
7. 검사 중 목록 변경은 저장 직전에 다시 확인한다. CLEAN 응답에서는 새 정확 예외를 적용하되, **이미 반환된 SUCCESS MALICIOUS/SUSPICIOUS 또는 엔진 실패는 도중 추가된 화이트리스트로 소급 해제하지 않는다.** 현재 업로드의 BLOCK/격리를 유지하고, 화이트리스트가 있었다면 원래 엔진 캐시는 stale로 남겨 이후 독립 요청의 정확 예외와 예외 해제 후 재검사를 보존한다. 검사 시작 전 정확 화이트리스트는 기존처럼 엔진 호출 없이 허용한다.
8. 최종 신규 차단 판정 저장 후 S3 격리를 수행한다. 유사도 차단도 기존 pending 격리 → ReportEvent → quarantine_files 경로에 들어간다. S3 실패는 BLOCK을 취소하지 않는다. S3/엔진 호출 동안 corpus 잠금은 잡지 않는다.

## 스키마와 이벤트 근거

기존 V1~V6는 수정하지 않았다.

### V7: 최종 정책과 원본 분석 분리

- `file_verdicts.verdict_source`, `download_events.decision_source`에 SIMILARITY를 추가한다.
- `similarity_assessments`: 서버 발급 assessment UUID, 파일 SHA256, 원본 analysis UUID, corpus token, policy version, 최근접 샘플 SHA256/TLSH 스냅샷, 거리, BLOCK/WARN/NONE/UNAVAILABLE, 평가 시각.
- 샘플에는 FK를 걸지 않아 기준 목록 해제나 지문 갱신이 과거 근거를 바꾸지 않는다. 원본 analysis와 파일 verdict는 FK로 연결한다.
- `similarity_cache`: SHA256 → 현재 캐시에 실제 사용한 assessment UUID. 과거 근거를 덮어쓰지 않고 현재 포인터만 갱신한다.
- `event_decision_evidence`: ReportEvent 이전에도 저장할 수 있는 event UUID → **인증 agent UUID / 원본 SHA256 / nullable assessment UUID** 매핑. 평가 없는 응답에서도 소유권은 남긴다. SHA는 바이패스 보고를 위해 nullable이며 file_verdicts 유무 때문에 원본 요청 SHA를 버리지는 않는다. 클라이언트가 소유자, 거리나 샘플을 제출하는 API는 없다.
- V7/V9는 아직 미커밋이며 격리된 테스트 DB 이외에 적용된 적 없다는 작업 전제에 따라 로컬에서 수정했다. V1~V6는 그대로이며 새 DB에서 V1~V9 순서로 실제 Flyway 실행을 검증했다. 이전 PR2 초안 V7~V9를 적용한 DB를 재사용하면 checksum 불일치가 나므로 폐기 가능한 테스트 DB를 재생성해야 한다. 운영 DB에 Flyway repair를 권하지 않는다.

### V8: corpus 일관성

- `similarity_corpus_state` 단일 revision과 `current_similarity_corpus_token` 뷰를 추가한다.
- 토큰은 revision, 현재 시각까지 만료된 활성 화이트리스트 개수, 정책 식별자로 구성한다. 새 쓰기가 없어도 자연 만료가 토큰을 바꾼다.
- 목록/지문 쓰기 BEFORE STATEMENT 트리거가 전역 배타 advisory transaction lock을 획득한다. 비교/캐시 판정은 같은 키의 공유 잠금을 잡는다.
- 블랙리스트 등록/해제/재활성화, 화이트리스트 변경, 활성 기준 샘플 지문 추가/변경/삭제는 revision을 바꾼다. 변경 사유만 수정해도 무효화하는 보수적 구현이다.
- 비기준 업로드 지문은 전역 revision을 매번 올리지 않는다. 대신 그 파일의 유효 지문이 새로 가능해지거나 바뀌거나 사라지면 해당 similarity_cache 포인터를 무효화한다. TOO_SHORT 등 산출 불가끼리의 보강은 동일 판정을 바꾸지 않으므로 기존 짧은 파일 캐시 보강을 유지한다.
- 가져오기는 기준 행 SHARE 잠금보다 먼저 전역 배타 잠금을 얻어 목록 트리거와 잠금 순서를 맞춘다.

### V9: 다운로드 당시 평가 확정

- `download_events.similarity_assessment_id` nullable FK를 추가한다.
- 이벤트 INSERT 트리거가 **그 event UUID, SHA, 인증 agent에 저장된 서버 매핑만** 읽는다. 보고한 최종 결정/출처와 평가 분류가 맞을 때만 연결한다. 정확 예외, fail-close, 바이패스에 이전 유사도 차단 근거를 잘못 붙이지 않는다.
- 같은 미보고 event UUID는 같은 인증 agent와 원본 SHA에서만 재시도할 수 있다. 마지막 평가로 pending 포인터를 갱신하며 엔진 단독, 정확 예외, 근거 없는 캐시는 포인터를 NULL로 비운다. 소유권 예약은 삭제하지 않는다. 이미 보고된 이벤트의 FK/근거는 바꾸지 않는다.
- **CheckHash에는 event_id가 없다.** 따라서 CheckHash 캐시 조회만 한 다운로드의 ReportEvent는 근거 FK가 NULL이다. 최신 SHA 분석으로 추측해 붙이지 않는다. 서버 캐시 포인터에는 실제 유사도 출처/WARN 근거가 보존되지만, 그 사실과 특정 미연결 이벤트의 근거 존재는 구분한다.
- 콘솔 표시와 REST DTO 노출은 PR3 범위로 남겼다. PR2에서 저장한 정확한 이벤트 근거는 다음처럼 읽어야 한다. 최신 analysis-by-SHA 조인은 사용하지 않는다.

```sql
SELECT e.event_id, a.*
FROM download_events e
LEFT JOIN similarity_assessments a
  ON a.assessment_id = e.similarity_assessment_id
WHERE e.event_id = :event_id;
```

## 캐시, 경쟁 조건과 비용

- CheckHash와 SubmitFile **양쪽**에서 CLEAN 또는 SIMILARITY 캐시는 현재 토큰에 맞는 실제 assessment가 있어야 쓴다. 최초 배포의 기존 CLEAN은 UNKNOWN/재업로드로 전환하므로 유사도 검사를 우회하지 않는다.
- 기존 ENGINE MALICIOUS/SUSPICIOUS 차단을 corpus 변경으로 해제하지 않는다. SIMILARITY 차단과 CLEAN/WARN만 corpus 의존 캐시다. 유사도 캐시 차단의 응답 출처도 SIMILARITY로 보존한다.
- 규칙 변경의 기존 stale 처리에 SIMILARITY를 포함한다. 유사도 차단도 원래 CLEAN YARA 분석에 의존하기 때문이다.
- 이벤트가 있는 요청의 잠금 순서는 event별 advisory lock → corpus → SHA별 advisory lock → 활성 룰셋 공유 잠금 → 판정/분석/근거 저장이다. 새 파일의 동시 업로드도 현재 verdict를 잠금 뒤 다시 읽어 저장한다. 예전처럼 임의의 PK 충돌을 삼키고 검증되지 않은 승자 캐시를 반환하지 않는다.
- 비교와 평가 저장이 같은 짧은 트랜잭션에 있어, 평가 스냅샷과 저장 사이에 corpus 쓰기가 끼지 못한다. 쓰기가 먼저 커밋되면 새 revision으로 비교하고, 나중에 커밋되면 방금 저장한 캐시도 다음 요청에서 token mismatch가 된다.
- 화이트리스트 시간 판단은 PostgreSQL 트랜잭션 시각을 사용한다. 트랜잭션 도중 만료가 지나도 다음 요청의 토큰은 달라지므로 오래된 허용/차단이 영구 캐시되지 않는다. 응답 전 순간까지 모든 벽시계 변화를 재검사하는 실시간 보장은 아니다.
- 후보를 자르지 않는다. JDBC fetchSize 256은 PostgreSQL 커서 전송 묶음이며 전체 후보를 끝까지 순회한다. 메모리에는 최솟값만 유지한다.
- 정확성 우선의 전역 잠금은 큰 corpus 비교 중 관리 쓰기를 대기시킨다. 지문 저장 쓰기도 이 잠금으로 조정된다. 대규모 지연/처리량 측정이나 인덱스 기반 근사 검색은 하지 않았고 조용한 후보 상한도 없다. 불필요한 무효화가 일부 있는 대신 복잡한 증분 corpus 관리자는 추가하지 않았다.

## proto와 구버전 agent

- verdict `DecisionSource`에 **6 = DECISION_SOURCE_SIMILARITY**만 추가했다. 0~5의 번호와 engine proto 계약은 바꾸지 않았다.
- gRPC 문자열 → enum, enum → 문자열 변환 양쪽에 SIMILARITY를 추가했다. 알려지지 않은 다른 출처 숫자는 기존 ReportEvent 어댑터처럼 NULL로 변환한다.
- `ProtoContractTest`는 현재 descriptor에서 6을 제거한 구버전 proto3 descriptor로 실제 응답 source=6을 파싱하고 다시 ReportEvent에 넣어 새 서버가 SIMILARITY로 받는 왕복을 실행한다.
- agent 소스 읽기 확인: `/srv/project/T-teamc/platform-agent/src/agent/addon.py`의 Outcome.source는 int(65행), CheckHash/Submit 응답 source를 그대로 Outcome에 넣는다(344~365행). ReportEvent에는 그 숫자를 그대로 넘긴다(382행). `verdict_client.py`는 decision 값의 유효성만 제한하며 source를 이름으로 강제 변환하지 않는다.
- 확인한 Python proto3 숫자 전달 경로와 계약 테스트에서는 wire 번호 확장이 호환된다. 실제 구버전 agent 프로세스를 띄운 end-to-end 검증은 아니다. agent/console에서 새 출처를 이름으로 표시하려면 proto 재생성과 SIMILARITY 표시 지원을 해당 소유자의 후속 릴리스에 포함해야 한다. agent 저장소는 수정하지 않았다.

## 독립 리뷰 결함 수정 (교정 cycle 1)

### 수정 결과와 보안 경계

- **YARA 소급 해제 차단:** `SubmitFileUseCase`에서 SUCCESS MALICIOUS/SUSPICIOUS도 실패 응답과 같이 현재 엔진 결과를 우선한다. mid-analysis whitelist가 있어도 현재 BLOCK/격리 유지, 이후 독립 요청은 정확 화이트리스트 ALLOW, 화이트리스트 삭제 후 stale 재검사다. 악성/의심 두 응답을 실제 fake-engine gRPC로 검증했다.
- **실제 인증/소유권:** 기존 `AgentAuthServerInterceptor`를 VerdictService에도 연결하고 공개 타입으로 재사용한다. Bearer 토큰을 DB의 agent_token_hash, 만료, REVOKED 상태로 검증하는 기존 로직은 바꾸지 않았다. `AgentAuthContext`의 ID를 SubmitFile 스트림 시작 시 보존하고 ReportEvent에도 사용한다. placeholder ID는 더 이상 Verdict 경로에서 사용하지 않는다.
- 신규 `EventDecisionEvidence.claim`은 실제 인증 agent + 원본 요청 SHA로 UUID를 예약하고 event별 잠금 아래 기존 행을 검증한다. 다른 agent 또는 다른 SHA 충돌은 **PERMISSION_DENIED**, 미인증/잘못된/만료/폐기 토큰은 **UNAUTHENTICATED**다. ReportEvent가 SHA를 file_verdicts FK 때문에 NULL로 바꾸기 전에 원본 SHA로 충돌을 검증한다. 충돌 INSERT를 무시한 성공으로 돌려주지 않는다.
- 예약은 모든 정상 완료 경로에 남는다. `SimilarityDecisions.bindCached`는 캐시 assessment가 없으면 NULL로 치환하며, 엔진 단독/엔진 장애/정확 예외 완료는 현재 미보고 포인터를 비운다. 이미 보고된 포인터와 download_events FK는 유지한다. 같은 보고 완료 UUID의 정확 화이트리스트 재업로드에서도 원래 BLOCK 근거가 보존되는 것을 검증했다.
- **발견한 기존 gap과 수정 범위를 구분한다:** 기존 Fleet RPC에는 인증이 있었으나 VerdictService는 인터셉터 미연결이고 ReportEvent는 개발 placeholder를 사용했다. 이번 수정은 이 Verdict gRPC 경계만 닫는다. 관리 REST의 `platform.auth.enabled=false` 로컬 모드는 기존 서블릿 보안 기능이며 변경하지 않았다. Fleet/Verdict용 인증 비활성화 모드는 기존에 없으므로 새 bypass를 만들지 않았다. 토큰을 보내지 않던 기존 Verdict 클라이언트는 이제 거절된다. wire 필드 변경 없이 기존 Bearer 메타데이터를 사용하므로 agent는 실제 발급받은 토큰을 보내야 한다. 소유자 ID를 클라이언트 proto 필드로 받지 않는다.
- CheckHash-only 보고도 인증 agent로 저장되지만 Submit 상관관계가 없으면 유사도 FK는 계속 NULL이다. 기존 SHA 기반 `pending_quarantine_uploads`의 수명/동시 격리 동작과 실제 agent 통합은 별도 기존 계약이며, 이번 변경을 agent별 격리 저장소 재설계나 전체 시스템 보안 감사라고 주장하지 않는다.

### 테스트와 실제 실패 근거

로그 경로는 `api-server/build/tlsh-evidence/`다.

| 결함 | RED (수정 전 실제 실행) | GREEN |
|---|---|---|
| mid-YARA whitelist가 현재 업로드를 ALLOW로 변경 | `fix-red1.log`, BLOCK 기대에 ALLOW | `fix-green1.log`; 최종 MALICIOUS/SUSPICIOUS 2개도 전체 suite 통과 |
| Verdict 인증 누락 | `fix-red2-auth.log`, missing/invalid/revoked/expired 4개 모두 거부되지 않음 | `fix-green2-auth.log`; 최종 테스트는 CheckHash/SubmitFile/ReportEvent 세 RPC 모두 UNAUTHENTICATED 검증 |
| event UUID의 agent/SHA 충돌 허용 | `fix-red2-owner.log`, 두 agent 동일 UUID 및 동일 owner 다른 SHA 2개가 예외 없이 수락됨 | `fix-green2-owner.log`; 최종은 타 agent의 pending/보고 완료 이벤트 ReportEvent, 원본 SHA 불일치도 거부 검증 |
| BLOCK A → 룰 변경 → YARA BLOCK → CACHE BLOCK → 보고 시 A가 잘못 연결됨 | `fix-red3.log`, NULL 기대에 이전 assessment UUID | `fix-green3.log`; 최종은 엔진 완료 직후 NULL, 캐시 이후 NULL, owner 유지, 과거 평가 행 보존 검증 |

- `fix-focused-final.log`: 유사도 29 + 인증 4 테스트, 실패/오류/skip 0. 이후 보고 완료 UUID 보존 assertion을 추가했고 아래 전체 실행에서 다시 검증했다.
- `fix-full-final.log`: `./gradlew test check bootJar` 실제 전체 테스트 **450 / 0 / 0 / 0**. `fix-final-junit/` XML을 프로그램으로 합산한 `fix-full-summary.json` 보존. bootJar는 기존 코드/빌드 입력이 같아 최종 명령에서 UP-TO-DATE 재사용됐으며, 앞선 `fix-full.log` 실행에서 패키징된 실제 산출물이다.
- 중간 focused 실행 1회는 ReportEvent 테스트의 개발 agent 상수 assertion 잔여로 컴파일 실패했다. 다른 1회는 강제 YARA 결과가 공유 무해 reference 파일 캐시에 남아 기존 4개 테스트에 간섭했다. 인증을 끄지 않고 assertion을 실제 인증 ID로 바꾸고 테스트 전용 강제 판정의 캐시를 stale로 정리하여 해결했다. 이를 유효 RED 근거로 세지 않는다.
- 모두 실제 PostgreSQL/Redis, 실제 gRPC, 기존 fake engine/S3 범위다. 외부 MalwareBazaar 자격증명/데이터나 악성 바이너리를 사용하지 않았다.

### 정확한 수정 파일 범위

전체 PR2는 **tracked 22 + untracked 9 = 31개 파일**이다. 최초 리뷰 범위의 19개를 보존했고 12개 경로가 추가됐다.

- 최초 PR2 안에서 수정: `SubmitFileCommand.java`, `SubmitFileUseCase.java`, `VerdictGrpcService.java`, `SimilarityDecisions.java`, `V7__similarity_decisions.sql`, `V9__event_similarity_evidence.sql`, `CheckHashGrpcIntegrationTest.java`, `TlshSimilarityGrpcIntegrationTest.java`, 이 보고서.
- 추가 production 경로: `event/application/ReportEventUseCase.java`, `fleet/interfaces/AgentAuthServerInterceptor.java`, `fleet/interfaces/AgentAuthContext.java`(설명 갱신), 신규 `verdict/application/EventDecisionEvidence.java`.
- 추가 test 경로: `EngineRulesetSyncIntegrationTest.java`, `FakeDetectionEngineService.java`, `QuarantineRestoreRestIntegrationTest.java`, `ReportEventGrpcIntegrationTest.java`, `SubmitFileGrpcIntegrationTest.java`, `TlshFingerprintGrpcIntegrationTest.java`, 신규 `VerdictAuthenticationGrpcIntegrationTest.java`, 신규 `VerdictTestClient.java`.
- 테스트 클라이언트는 공통 fixture로 실제 agent/토큰을 DB에 등록하고 헤더만 붙인다. 기존 정책 assertion을 지우거나 서버 인증을 우회하지 않았다. V8, 정책 임계값, 기존 REST 인증 구현, console/engine/다른 worktree는 이 교정에서 변경하지 않았다.

## 독립 재리뷰 격리 순서 결함 수정 (교정 cycle 2, 현재 결과)

### 수정과 동시성 경계

- 재현한 결함: 신규 BLOCK 커밋 → S3 대기 → 같은 인증 agent/event의 CACHE BLOCK 재시도 → ReportEvent 먼저 커밋 → 뒤늦게 S3 성공. 이전 코드는 SHA pending만 남기고 이미 보고된 이벤트를 격리에 연결하지 못했다.
- `EventQuarantineUploads`를 추가했다. S3 성공 뒤 **짧은 트랜잭션에서** 기존 `EventDecisionEvidence.claim`으로 인증 agent/event/원본 SHA를 다시 검증하고 event별 advisory transaction lock을 획득한다. event pending을 저장한 뒤 이미 보고된 일치 BLOCKED 이벤트가 있으면 즉시 연결한다. ReportEvent도 같은 잠금 안에서 이벤트를 flush한 뒤 같은 연결 함수를 호출한다. 따라서 어느 트랜잭션이 먼저 커밋해도 다른 쪽이 pending 또는 보고 이벤트를 확인하며, 확인 후 INSERT 사이에 경쟁 창이 없다.
- 미배포 초안 V9에서 `pending_quarantine_uploads.event_id` FK/unique를 추가했다. 같은 SHA의 서로 다른 이벤트 업로드를 덮어쓰지 않도록 이전 SHA PK 대신 **event unique + event NULL인 legacy SHA partial unique**를 사용한다. 새 인증 SubmitFile 업로드는 자신의 event에만 귀속된다. 다른 agent가 같은 SHA로 별도 event를 보고해도 이 pending을 소비하지 않는다.
- `quarantine_files`는 보고 이벤트의 filename/agent/SHA, 그 event에 검증된 업로드 크기와 bucket/key로 생성한다. 최신 file_verdicts의 파일명/크기를 추측하지 않는다. event 잠금 아래 기존 event 격리 여부를 확인해 같은/다른 object의 중복 완료도 새 격리 행을 만들지 않는다. 기존 S3 object unique도 유지한다. 보고 완료 시 event pending은 지우며 RELEASED/FAIL_CLOSE에 남은 업로드를 다른 이벤트로 넘기지 않는다. 과거 보고 결정과 similarity assessment는 수정하지 않는다.
- S3와 엔진은 계속 트랜잭션/corpus 잠금 밖이다. S3 실패나 완료 기록 DB 실패는 현재 BLOCK을 취소하지 않는다. 기존 YARA 우선, fail-close, 실제 인증과 소유권 검사, 평가 포인터 교정은 그대로다.
- 내부 event 없는 호출/기존 NULL-event pending만 SHA legacy 경로를 유지한다. legacy 조회는 `DELETE ... RETURNING`으로 원자 소비하며 새 event pending을 건드리지 않는다. 이미 event 격리가 연결되었으면 legacy 격리를 추가하지 않는다.
- `AgentGrpcService`의 VerdictService가 인터셉터 영향을 받지 않는다는 오래된 주석을 수정했다.

### 실제 실행 근거와 한계

로그/XML은 모두 `api-server/build/tlsh-evidence/` 아래이며 이전 이력은 삭제하지 않았다.

- **RED:** `./gradlew test --tests '*TlshSimilarityGrpcIntegrationTest.late_successful_upload*'` → `fix2-red-late.log`, 2개 모두 실제 실패. fake S3를 latch로 멈추고 retry/ReportEvent를 완료한 뒤 S3를 풀었으나 실제 quarantine 행이 없어 `EmptyResultDataAccessException`이 났다. YARA와 SIMILARITY를 각각 재현했으며 프로덕션 변경 전에 실행했다.
- **GREEN:** 동일 명령 → `fix2-green-late-final.log`, 2개 통과. 실제 격리 행, 이벤트 당시 파일명/검증 크기, 소유자/SHA, 해당 event pending 삭제, 기존 평가 보존, duplicate ReportEvent ALREADY_EXISTS와 중복 격리 없음, 타 agent 보고 거절을 확인했다. S3 대기 중 corpus 쓰기도 완료한다.
- 추가 동시성 회귀 2개는 report-first/completion-first 각각 첫 트랜잭션을 커밋 직전 latch로 멈추고 두 번째를 실행한다. PostgreSQL `pg_locks`에서 **해당 event advisory lock의 실제 대기**를 확인한 뒤 첫 커밋을 허용한다. 두 순서 모두 격리 1행/pending 0행이며 동일 object/다른 object 재완료에도 멱등이다. 추가 소유권 회귀 1개는 다른 인증 agent의 같은 SHA/다른 event 보고가 pending을 훔치지 못함을 확인한다. 이 추가 회귀가 처음부터 RED였다고 주장하지 않는다.
- **최종 전체:** api-server에서 `./gradlew test check bootJar --rerun-tasks` → `fix2-full-final.log`, **BUILD SUCCESSFUL, 15 actionable tasks: 15 executed**. XML 56 suite를 코드로 합산한 결과 **455 tests / failures 0 / errors 0 / skipped 0**. 유사도 클래스 **34개**. XML 스냅샷 `fix2-final-junit/`, 합계 `fix2-full-summary.json`. `fix2-focused.log`의 34개 중 1개 실패는 다른 이벤트의 기존 pending까지 SHA 전체로 세던 새 assertion 때문이며 event별 cleanup assertion으로 고쳐 최종 전체에서 통과했다.
- 첫 GREEN 시도 `fix2-green-late.log`는 V4의 실제 PK 제약명 대신 추정한 이름으로 DROP하여 Flyway 초기화가 실패했다. V4는 수정하지 않고 V9에서 실제 `pk_pending_quarantine_uploads` 이름을 사용해 해결했다. 유효 RED 근거로 세지 않는다. V7~V9는 미배포 초안이고 폐기 가능한 새 Testcontainers DB에 V1~V9가 실행됐다. 운영 Flyway repair나 기존 운영 migration 수정을 처방하지 않는다.
- 전체 PR2 현재 범위: **tracked 25 + untracked 10 = 35개**. cycle 2 수정 경로는 `SubmitFileUseCase.java`, `ReportEventUseCase.java`, `PendingQuarantineUploadPortImpl.java`, `PendingQuarantineUploadLookupPortImpl.java`, `AgentGrpcService.java`, 신규 `EventQuarantineUploads.java`, 초안 `V9__event_similarity_evidence.sql`, `TlshSimilarityGrpcIntegrationTest.java`, 이 보고서다.
- tracked diff와 untracked 전체의 diff-check 및 credential/shell/eval/deserialization/SQL interpolation 패턴 스캔을 실행했다. `fix2-scan.json`의 유일한 credential 패턴은 기존 `VerdictTestClient`의 무작위 UUID를 붙이는 테스트 토큰 접두사이며 실제 비밀이 아니다. 나머지 일치는 없다. `fix2-diff-check.json` 보존. 독립 보안 감사/리뷰를 대신하지 않는다.
- 새 bootJar: `api-server/build/libs/api-server-0.0.1-SNAPSHOT.jar`, SHA256 `9b0ef37b69efd46a135ad513b929ce4d48b2496694ff4e5f69ba7875c7ba2241`.
- 검증은 실제 PostgreSQL/Redis/gRPC와 fake engine/S3다. 실제 S3/Python YARA/agent/console 통합은 하지 않았다. JVM 공유/Gradle deprecation 경고와 기존 SSE 관련 Tomcat graceful shutdown 30초 timeout 로그가 남았으나 테스트 실패/skip은 없다.
- durable S3 completion outbox/재시도나 object 삭제 정책을 새로 만들지 않았다. S3 성공 직후 프로세스 종료/DB 실패, 동일 event의 중복 실제 업로드 등에서는 참조 없는 object가 남을 수 있다. **성공 완료 트랜잭션이 커밋한 업로드의 보고 순서 경쟁을 고친 것**이지 S3/DB 분산 원자성 보장은 아니다. 보고되지 않은 event pending 보존 기간과 legacy NULL-event pending의 소유권 정보 부재는 기존 운영 제한이다. 새 인증 업로드는 legacy로 기록하지 않는다.
- 부모 에이전트의 최종 독립 재리뷰가 남아 있다. 미커밋 상태이며 커밋/발행/배포/태스크 상태 변경/다른 worktree 수정은 하지 않았다.

## 실제 RED → GREEN 근거 (최초 구현 이력)

아래 red9/green9의 '새 정확 예외 후 옛 YARA 캐시 부활 금지'는 당시 잘못된 현재 업로드 ALLOW 기대도 포함했던 과거 기록이다. **현재 YARA 정책의 근거로 사용하지 않으며 위 cycle 1 회귀로 대체했다.**


원본 로그는 이 worktree의 무시되는 `api-server/build/tlsh-evidence/`에 있다. 명령은 api-server 기준 `./gradlew test --tests '*TlshSimilarityGrpcIntegrationTest.<해당 메서드>*'` 또는 클래스 전체다.

| 수직 동작 | RED | GREEN |
|---|---|---|
| 실제 패딩 변종이 CLEAN 엔진 후 거리17로 차단, 원본 분석 보존 | red1.log, ALLOW가 나와 실패 | green1b.log |
| corpus 변경 후 직접 업로드 캐시 무효화, 유사도 출처 유지 | red2.log | green2.log |
| 직접 SubmitFile의 정확 화이트리스트 우선 | red3.log | green3.log |
| 유사도 신규 격리와 ReportEvent 이벤트 근거 | red4b.log, 격리 행 0 | green4.log |
| 후보 없음도 평가 완료 캐시로 재사용 | red5.log | green5.log |
| 입력 지문이 UNAVAILABLE → CALCULATED로 바뀔 때 재평가 | red6b.log | green6b.log |
| 규칙 변경이 유사도 차단도 stale로 변경 | red7b.log | green7.log |
| 과거 stale CLEAN 위 엔진 RPC 실패를 ERROR로 기록 | red8.log | green8.log |
| 검사 중 새 정확 예외 후 옛 YARA 캐시 부활 금지 | red9.log | green9.log |
| 실패한 엔진 응답을 검사 중 예외로 ALLOW로 바꾸지 않음 | red10.log | green10.log |
| 동일 pending 이벤트 재시도에서 실제 마지막 결정 연결 | red11.log | green11.log |
| 정확 예외 재시도의 RELEASED 이벤트에 과거 BLOCK 근거 미연결 | red12.log | green12.log |
| WARN31/70 경계의 별도 분류 | red13-warning-restart.log, 4개 중 31/70 2개 실패 | green13-warning-restart.log |

TDD 기록을 과장하지 않는다. 최초 BLOCK 슬라이스 때 WARN 분기가 함께 작성되어, 나중에 그 분기를 삭제한 뒤 경계 통합 테스트의 RED를 확인하고 명시적 정책 분기로 다시 구현했다. 마지막 WARN 로그는 **이 재시작 증거**이지 처음부터 기능이 없었다는 기록이 아니다. 동률, 대규모 묶음, 구버전 descriptor, 복원 등 추가 회귀 테스트 모두가 처음 실행부터 RED였다고 주장하지 않는다.

중간 시행착오: 첫 analysis FK 오류는 JDBC가 참조하기 전에 JPA analysis를 flush하도록 고쳤다. red4.log는 agent fixture FK 누락, red6.log는 테스트 관리자 역할 누락, red7.log는 테스트 트랜잭션 누락으로 유효한 기능 RED가 아니며 b 로그를 구분했다. MANDATORY 경계 추가 뒤 spy stubbing이 프록시 밖에서 트랜잭션을 요구하던 테스트 설정은 실제 target spy에 훅을 걸도록 고쳤다(refactor2.log 통과).

## cycle 1 실행 결과 (450개, 이후 cycle 2의 455개 실행으로 대체)

- 수정 후 최종 전체: `./gradlew test check bootJar`, **450 tests / failures 0 / errors 0 / skipped 0**, `fix-full-final.log`. 실제 `:test` 실행이며 UP-TO-DATE 재사용이 아니다. `full-final-3.log` 442개는 수정 전 기록이다.
- 유사도 gRPC 통합 **29개**, Verdict 인증 통합 **4개**: 실제 PostgreSQL 17/Redis 8 Testcontainers, 실제 gRPC transport와 공식 Java TLSH를 사용했다. YARA 응답은 기존 fake engine, S3는 fake upload다. Python 실제 YARA나 실제 S3 서비스를 실행한 end-to-end라고 주장하지 않는다.
- 실제 무해 fixture + 공백64 바이트 패딩의 SHA는 다르고 Java 측정 거리는 **17**이다. 30/31/70/71 경계는 공식 거리 함수로 목표 거리가 되는 합성 TLSH 메타데이터를 찾은 정책 테스트이며 실제 악성 바이너리 변종 실험과 구별한다.
- 기준 지문 첫 import/갱신/블랙리스트 추가·해제·재활성화, 유효 기준 화이트리스트 제외와 자연 만료, 입력 지문 가용성 변경, legacy 정규화, CheckHash/Submit 양쪽, WARN/BLOCK 캐시 원본 근거 재사용을 검증했다.
- 비교 완료 후 커밋 전 corpus writer가 잠금에 대기하고, 커밋 뒤 다음 캐시 조회가 무효화되는 경쟁을 latch로 검증했다. 엔진 수행 중 목록 변경, 동일 파일 동시 업로드의 두 이벤트 근거도 검증했다.
- 실제 유사도 SubmitFile → ReportEvent → 격리 행 → 기존 복원 유스케이스 → 정확 SHA 화이트리스트 → CheckHash/직접 Submit 허용과 과거 BLOCK 근거 보존을 실행했다. 복원 HTTP 경계의 기존 REST 테스트도 전체 suite에서 통과했다.
- 301개 후보 데이터로 fetch 묶음을 넘는 전체 비교를 확인했다. 실환경 성능 벤치마크는 아니다.
- 수정 후 마지막 전체 실행 XML은 `fix-final-junit/`, 프로그램 합계는 `fix-full-summary.json`이다. `final-junit/`, `final-summary.json`은 수정 전 442개 실행 이력이다. 이전 full1은 기존 테이블 목록/옛 CLEAN 캐시 기대 2개 실패, full2는 짧은 지문 캐시 보강 회귀 1개 실패였고 최종 실행에서 모두 해결했다.
- bootJar 산출물: `api-server/build/libs/api-server-0.0.1-SNAPSHOT.jar`. `check`와 패키징 통과, 별도 서비스 기동/배포는 하지 않았다.
- `git diff --check` 통과. 전체 PR2의 tracked diff와 모든 untracked 파일을 포함해 자격증명/shell/eval/직렬화/SQL 문자열 패턴을 검사했다. 자격증명 패턴 1건은 `VerdictTestClient`의 테스트 토큰 접두사 + 매회 무작위 UUID 생성이며 저장된 실자격증명이 아니다. 실행 가능한 보안 결함으로 판단한 일치는 없다. SQL 값은 JDBC 바인딩을 쓴다. 스캔 결과는 `fix-scan.json`에 파일/행/유형만 기록했다.
- 기존 JDK 공유/Gradle deprecation 경고는 남아 있다. 예외 주입 테스트의 엔진/S3 오류 로그는 의도한 실패 경로다.

## 변경 파일

- `api-server/src/main/java/com/teecherteamc/platform/verdict/application/SimilarityDecisions.java` 신규: 공식 비교, 잠금/토큰, 평가/요청 근거 저장.
- 같은 application의 `SubmitFileUseCase.java`, `SubmitFileCommand.java`: event UUID 전달, 정확 목록 재검증, YARA 뒤 추가 정책, 캐시/격리/경쟁 처리.
- verdict infrastructure의 `AnalysisRepositoryImpl.java`, `FileVerdictJpaRepository.java`, `JdbcFileFingerprintStore.java`: FK 선반영, 룰 변경 stale 범위, import 잠금 순서.
- `verdict/interfaces/VerdictGrpcService.java`: event UUID 커맨드 전달, 양방향 SIMILARITY enum 변환.
- lookup의 `application/CheckHashUseCase.java`, `domain/CachedVerdict.java`, `infrastructure/VerdictCacheLookupPortImpl.java`: 토큰 검증, 기존 캐시 최초 전환, 유사도 출처 보존.
- `api-server/src/main/resources/db/migration/V7__similarity_decisions.sql`, `V8__similarity_corpus_coherence.sql`, `V9__event_similarity_evidence.sql` 신규.
- `proto/teecher/verdict/v1/verdict.proto`: additive 출처 번호6.
- `api-server/src/test/java/com/teecherteamc/platform/TlshSimilarityGrpcIntegrationTest.java` 신규.
- 같은 test 패키지의 `CheckHashGrpcIntegrationTest.java`, `SchemaMigrationTest.java`, `ProtoContractTest.java`.
- `docs/tlsh-pr2-report.md` 신규.
- PR1 vendor 원본/라이선스/고정 revision, V1~V6, engine proto, Python engine, console은 변경하지 않았다.

## 남은 제한과 제안 커밋

- 실제 외부 MalwareBazaar 호출/데이터 가져오기는 하지 않았다. 인증된 공급자의 SHA256/TLSH 메타데이터를 승인된 import로 넣는 운영 절차가 필요하다. 악성 바이너리 다운로드, 외부 전체 해시 블랙리스트 편입, 유사도 변종 자동 등록은 없다.
- CheckHash-only 이벤트의 유사도 근거는 정확한 매핑이 없어 NULL이다. 이를 채우려면 후속 계약에서 CheckHash에 event UUID 등 요청 상관관계를 전달해야 하며 최신 SHA 평가로 대체하면 안 된다.
- 콘솔 표시, REST 근거 DTO, 실제 agent/실제 YARA/실제 S3 결합 검증, 큰 corpus 부하 검증은 수행하지 않았다. UI PR3나 운영 배포를 독립 완료로 표시하지 않는다.
- 전역 잠금의 지연 비용, 비보고 pending 이벤트/평가 이력 보존 기간은 운영 규모에 맞춰 후속 관리가 필요하다. 현재 행을 임의로 자동 삭제하지 않는다.
- 독립 리뷰 대기. 승인 전 커밋 금지. 제안 제목:

`feat: TLSH 유사 파일을 차단하고 다운로드 당시 판단 근거를 보존한다`

제안 본문:

- YARA 통과 파일에 최근접 TLSH 거리 정책과 별도 경고 기록을 적용한다
- 기준 샘플과 정확 예외 변경에 맞춰 캐시를 재검증하고 경쟁 조건을 보호한다
- 원본 엔진 분석, 격리, 이벤트별 평가 스냅샷과 구버전 enum 호환성을 검증한다
