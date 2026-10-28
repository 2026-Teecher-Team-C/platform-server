# YARA 룰셋 관리 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 콘솔에서 `.yar`를 올려 룰셋 버전을 만들고, 버전을 활성화하고, 룰마다 켜고 끄며, 그 결과가 탐지 엔진 판정에 반영되게 한다.

**Architecture:** 컴파일 검증과 룰 적재는 탐지 엔진의 새 unary RPC 두 개가 맡는다. api-server는 원문을 DB에 두고, 매 `Analyze` 메타데이터에 기대 룰셋 키를 실어 보낸다. 엔진이 다른 키를 들고 있으면 `FAILED_PRECONDITION`으로 거절하고, api-server가 `LoadRuleset`으로 밀어 넣은 뒤 같은 스풀 파일로 한 번 더 보낸다. 활성화와 활성 버전 룰 변경은 도메인 이벤트로 verdict 컨텍스트에 알려 `ENGINE` 판정을 stale로 만들고, 커밋 뒤 엔진에 바로 적재를 시도한다.

**Tech Stack:** Spring Boot 4 / JPA / Flyway / grpc-java (api-server), Python 3.12 + yara-python 4.5.4 + grpcio (detection-engine), React 19 + Vite + vitest + MSW (console)

**Spec:** `docs/superpowers/specs/2026-10-28-ruleset-management-design.md`

## Global Constraints

- 버전 번호는 정수 `yara_rulesets.rulesets_version`. 새 버전 = 현재 최대값 + 1. v0 행은 지우지 않고 목록에서 숨긴다.
- 업로드: multipart 필드 `file` 하나, 확장자 `.yar`, 1 MiB(1,048,576 바이트) 이하, 비어 있지 않음.
- 심각도: 룰 `meta: severity`를 대문자로 바꿔 LOW/MEDIUM/HIGH/CRITICAL이면 그 값, 아니면 MEDIUM.
- 새 버전의 룰은 모두 `enabled = true`로 시작한다. 이전 버전 설정을 물려받지 않는다.
- stale 대상은 `file_verdicts.verdict_source = 'ENGINE'`인 행만.
- api-server는 룰 원문을 해석하지 않는다. 컴파일·룰 추출은 엔진이 한다. 엔진은 `include`를 허용하지 않는다.
- fail-close 고정: 엔진에 닿지 못하면 검사는 기존처럼 실패(차단)한다. 키 불일치 시 "적재 후 1회 재전송"은 상태 동기화이지 장애 재시도가 아니다 — 연결 실패(`UNAVAILABLE` 등)에는 재시도하지 않는다.
- 관리 API는 로그인만 확인한다(역할 구분 없음). 목록은 `PageResponse`, 오류는 `ApiException(ErrorCode.X, msg)`.
- 쓰기 작업은 같은 트랜잭션에서 `AuditLogRecorder.record(...)`. 원문은 detail에 넣지 않는다.
- 기존 Flyway 파일은 고치지 않는다. 새 파일은 `V5__ruleset_management.sql`.
- proto 필드 번호 재사용·타입 변경 금지. 새 필드·메시지만 추가.
- 코드 스타일: 우테코 스타일(4칸, 줄바꿈 8칸, 120자), 테스트 메서드 이름 한글 가능. 주석은 주변 코드 밀도에 맞춘다.

## Review Focus

- 엔진 재시작으로 번들 룰(키 `""`)로 돌아간 뒤 첫 검사 → 키 불일치 → 적재 → 같은 파일 재전송 → 정상 판정 (Task 7 테스트)
- 이미 활성인 버전을 다시 활성화 → 200, stale 처리·감사 로그 없음 (Task 6 테스트)
- `include "..."`가 든 `.yar` 업로드 → 컴파일 실패로 거부, 엔진이 파일을 읽지 않음 (Task 1 엔진 테스트 `test_include는_거부한다`)
- 대기 버전의 룰을 끄고 켬 → stale 처리도 엔진 적재도 일어나지 않음 (Task 6 테스트)
- 1 MiB를 넘는 파일 → 500이 아니라 400 (Task 5 테스트)

---

### Task 1: 엔진 — 컴파일 검증·룰셋 적재 RPC와 키 검사

**Files:**
- Modify: `proto/teecher/engine/v1/engine.proto`
- Modify: `detection-engine/src/detection_engine/analyzer.py`
- Modify: `detection-engine/src/detection_engine/server.py`
- Test: `detection-engine/tests/test_ruleset.py` (새 파일), `detection-engine/tests/test_analyze.py` (기존 그대로 통과해야 함)

**Interfaces:**
- Produces (proto, api-server Task 4·7이 사용):
  ```proto
  service DetectionEngine {
    rpc Analyze(stream AnalyzeRequest) returns (AnalyzeResponse);
    rpc CompileRules(CompileRulesRequest) returns (CompileRulesResponse);
    rpc LoadRuleset(LoadRulesetRequest) returns (LoadRulesetResponse);
  }
  message AnalyzeMetadata { string sha256 = 1; int64 file_size = 2; string ruleset_key = 3; }
  message CompileRulesRequest { string source = 1; }
  message CompiledRule { string name = 1; string severity = 2; }
  message CompileRulesResponse { bool ok = 1; string error_message = 2; repeated CompiledRule rules = 3; }
  message LoadRulesetRequest { int32 rulesets_version = 1; string ruleset_key = 2; string source = 3;
                               repeated string disabled_rule_names = 4; }
  message LoadRulesetResponse {}
  ```
- 규칙: `ruleset_key`가 빈 문자열이면 검사하지 않는다(기존 클라이언트·테스트 호환). 비어 있지 않고 엔진의 현재 키와 다르면 청크를 읽기 전에 `context.abort(grpc.StatusCode.FAILED_PRECONDITION, "ruleset mismatch")`. `LoadRuleset` 컴파일 실패는 `INVALID_ARGUMENT`. 원문 1 MiB 초과는 `CompileRules`에서 `ok=false`, `LoadRuleset`에서 `INVALID_ARGUMENT`. `AnalyzeResponse.rulesets_version`에는 현재 적재된 버전을 채운다(시작 시 0).
- Python: `Analyzer.compile_source(source: str) -> list[tuple[str, str]]` (staticmethod, 실패 시 `yara.Error` 계열 예외), `Analyzer.load(version: int, key: str, source: str, disabled: Iterable[str]) -> None`, 프로퍼티 `version: int`, `key: str`. 내부 상태(compiled rules, disabled frozenset, version, key)는 튜플 하나로 묶어 한 번에 바꿔 끼운다(분석 중 교체에도 일관된 스냅샷).

- [ ] **Step 1: proto 수정 후 코드 생성** — `cd detection-engine && ./scripts/gen_proto.sh` 성공.
- [ ] **Step 2: 실패하는 테스트 작성** (`tests/test_ruleset.py`, `test_analyze.py`의 `stub`/`stream` 패턴을 그대로 쓰되 `stream`에 `ruleset_key` 인자 추가)
  - `test_컴파일_검증은_룰_이름과_심각도를_돌려준다`: 원문에 `rule A { meta: severity = "critical" condition: true }`, `rule B { condition: false }` → `ok=True`, rules == `[("A","CRITICAL"), ("B","MEDIUM")]`
  - `test_문법_오류는_ok_false와_메시지를_준다`: `rule { }` → `ok=False`, `error_message != ""`
  - `test_include는_거부한다`: `include "/etc/passwd"\nrule A { condition: true }` → `ok=False`
  - `test_1MiB를_넘는_원문은_거부한다`: `"//" + "x" * (1024*1024)` → `ok=False`
  - `test_키가_다르면_FAILED_PRECONDITION`: 새 서버에 `ruleset_key="1:abc"`로 Analyze → `grpc.RpcError.code() == FAILED_PRECONDITION`
  - `test_적재_후에는_새_룰로_판정하고_버전을_채운다`: `LoadRuleset(7, "7:k", 'rule Only { strings: $a = "ONLY-MARK" condition: $a }', [])` 뒤 `ruleset_key="7:k"`로 `b"ONLY-MARK"` → MALICIOUS, `rulesets_version == 7`; EICAR_MARKER → CLEAN
  - `test_꺼진_룰의_매치는_판정에서_뺀다`: 두 룰 적재, 하나를 disabled로 → 그 룰만 맞는 입력은 CLEAN이고 `matches == []`
  - `test_키가_비어_있으면_검사하지_않는다`: 적재 후에도 `ruleset_key=""` 요청은 정상 처리
  - 각 테스트는 자체 서버를 띄운다(module 스코프 `stub`을 쓰면 적재 상태가 다른 테스트로 샌다).
- [ ] **Step 3: 실패 확인** — `cd detection-engine && uv run --group dev pytest -q tests/test_ruleset.py` → FAIL (`CompileRules` 없음 등)
- [ ] **Step 4: 구현** — `yara.compile(source=..., includes=False)`; 룰 목록은 컴파일된 Rules를 순회해 `rule.identifier`, `rule.meta.get("severity")`. 서버는 Analyze에서 첫 요청이 metadata면 키를 비교한다.
- [ ] **Step 5: 통과 확인** — `uv run --group dev ruff check . && uv run --group dev pytest -q` → 전부 PASS (기존 `test_analyze.py` 포함)
- [ ] **Step 6: Commit** — `git add proto detection-engine && git commit -m "Feat: 탐지 엔진 룰셋 컴파일 검증·적재 RPC와 룰셋 키 검사"`

### Task 2: 스키마 — 원문 컬럼과 v1 시드

**Files:**
- Create: `api-server/src/main/resources/db/migration/V5__ruleset_management.sql`
- Modify: `api-server/src/test/java/com/teecherteamc/platform/SchemaMigrationTest.java`

**Interfaces:**
- Produces: `yara_rulesets.source TEXT` (nullable — v0은 원문이 없다), `yara_rulesets.uploaded_by UUID NULL REFERENCES admin_users(admin_id)`. v1 행: `rulesets_version=1, rule_count=2, is_active=true, activated_at=now(), notes='기본 번들 룰(eicar.yar + teecher_demo.yar)'`, `source` = 두 파일 내용을 그대로 이어 붙인 문자열. `yara_rules`에 v1의 `EICAR_Test_File`(HIGH), `Teecher_Demo_Fake_Malware`(HIGH), `rule_id = gen_random_uuid()`, enabled=true.

- [ ] **Step 1: 실패하는 테스트** — `SchemaMigrationTest`에 `V5_후_활성_룰셋은_v1_하나이고_룰_2개를_가진다`: `SELECT rulesets_version FROM yara_rulesets WHERE is_active` == `[1]`; `SELECT count(*) FROM yara_rules WHERE rulesets_version = 1` == 2; v1 `source`에 `"rule EICAR_Test_File"`과 `"rule Teecher_Demo_Fake_Malware"` 포함.
- [ ] **Step 2: 실패 확인** — `make test-api` 또는 `cd api-server && ./gradlew test --tests '*SchemaMigrationTest'` → FAIL
- [ ] **Step 3: 마이그레이션 작성** (원문은 `detection-engine/rules/*.yar`를 복사; 달러 인용 `$yar$...$yar$` 사용)
- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS
- [ ] **Step 5: Commit** — `git commit -m "Feat: 룰셋 원문 컬럼과 기본 룰 v1 시드 마이그레이션"`

### Task 3: ruleset 도메인과 조회 API

**Files:**
- Create: `api-server/src/main/java/com/teecherteamc/platform/ruleset/domain/Ruleset.java`, `YaraRule.java`, `RulesetRepository.java`, `YaraRuleRepository.java`
- Create: `.../ruleset/infrastructure/` JPA 리포지토리 구현 (기존 컨텍스트의 `*JpaRepository` + `*RepositoryImpl` 패턴)
- Create: `.../ruleset/application/ListRulesetsUseCase.java`, `ListYaraRulesUseCase.java`, `RulesetNotFoundException.java`
- Create: `.../ruleset/interfaces/RulesetController.java`, `RulesetItem.java`, `YaraRuleItem.java`
- Test: `api-server/src/test/java/com/teecherteamc/platform/RulesetListRestIntegrationTest.java`

**Interfaces:**
- `Ruleset` (`yara_rulesets`): `int getVersion()`, `int getRuleCount()`, `boolean isActive()`, `Instant getActivatedAt()`, `UUID getActivatedBy()`, `String getSource()`, `UUID getUploadedBy()`, `Instant getCreatedAt()`; `static Ruleset upload(int version, String source, int ruleCount, UUID uploadedBy, Instant now)`; `void activate(UUID adminId, Instant now)`; `void deactivate()`.
- `YaraRule` (`yara_rules`): `UUID getRuleId()`, `int getRulesetVersion()`, `String getRuleName()`, `String getSeverity()`, `boolean isEnabled()`; `static YaraRule of(int version, String name, String severity)`; `void changeEnabled(boolean enabled)`.
- `RulesetRepository`: `Optional<Ruleset> findByVersion(int)`, `Optional<Ruleset> findActive()`, `int findMaxVersion()`, `Page<Ruleset> findListed(Pageable)` (version > 0, version 내림차순), `Ruleset save(Ruleset)`, `void deactivateAll()` (bulk update, flush 포함).
- `YaraRuleRepository`: `List<YaraRule> findByVersion(int)` (이름 오름차순), `Optional<YaraRule> findByVersionAndId(int, UUID)`, `Map<Integer, Long> countDisabledByVersions(Collection<Integer>)`, `List<String> findDisabledNames(int)`, `void saveAll(List<YaraRule>)`, `YaraRule save(YaraRule)`.
- JSON `RulesetItem`: `{ "version": 3, "ruleCount": 12, "disabledRuleCount": 2, "isActive": true, "createdAt": "...", "activatedAt": "..."|null }`
- JSON `YaraRuleItem`: `{ "ruleId": "uuid", "ruleName": "EICAR_Test_File", "severity": "HIGH", "enabled": true }`
- `GET /api/admin/rulesets?page=0&size=20` → `PageResponse<RulesetItem>` (size 1..100, 기본 20). `GET /api/admin/rulesets/{version}/rules` → `PageResponse<YaraRuleItem>` 한 페이지에 전부(page=0, size=룰 수, 최소 1). 없는 버전·v0 → 404.

- [ ] **Step 1: 실패하는 통합 테스트** (`WhitelistListRestIntegrationTest` 스타일, `AdminApiClient` 사용)
  - `목록은_v0을_빼고_최신_버전부터_준다`: v2(대기, 룰 3개 중 1개 꺼짐)를 시드 → content[0].version == 2, disabledRuleCount == 1; content[1].version == 1, isActive == true; v0 없음
  - `룰_목록은_이름순이고_사용_여부를_준다`: v1 → ruleName == `["EICAR_Test_File","Teecher_Demo_Fake_Malware"]`, severity == HIGH
  - `없는_버전의_룰_목록은_404`, `로그인_없이_요청하면_401`
- [ ] **Step 2: 실패 확인** — `./gradlew test --tests '*RulesetListRestIntegrationTest'` → FAIL (404/매핑 없음)
- [ ] **Step 3: 구현** — `package-info.java`의 애그리거트 설명에 `YaraRule`을 함께 적는다.
- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS
- [ ] **Step 5: Commit** — `git commit -m "Feat: 룰셋·룰 목록 조회 API"`

### Task 4: 엔진 룰셋 포트와 테스트용 가짜 엔진

**Files:**
- Create: `.../ruleset/domain/EngineRulesetPort.java`, `CompiledRule.java`, `CompileResult.java`, `RulesetSnapshot.java`, `EngineRulesetUnavailableException.java`
- Create: `.../ruleset/infrastructure/GrpcEngineRulesetPortImpl.java`
- Modify: `api-server/src/main/java/com/teecherteamc/platform/ApiServerApplication.java` (`@ImportGrpcClients` types에 `DetectionEngineGrpc.DetectionEngineBlockingStub.class` 추가)
- Modify: `api-server/src/test/java/com/teecherteamc/platform/FakeDetectionEngineService.java`
- Modify: `api-server/src/main/java/com/teecherteamc/platform/common/ErrorCode.java`
- Test: `api-server/src/test/java/com/teecherteamc/platform/ruleset/infrastructure/GrpcEngineRulesetPortImplTest.java` (in-process 가짜 엔진으로)

**Interfaces:**
- `record CompiledRule(String name, String severity)`; `record CompileResult(boolean ok, String errorMessage, List<CompiledRule> rules)`
- `record RulesetSnapshot(int version, String source, List<String> disabledRuleNames)` + `String key()`: `version + ":" + sha256Hex(정렬된 disabledRuleNames를 "\n"으로 join)`의 앞 16자. 같은 입력이면 순서와 무관하게 같은 키.
- `EngineRulesetPort`: `CompileResult compile(String source)`, `void load(RulesetSnapshot snapshot)`. 둘 다 연결 실패·타임아웃(10초) 시 `EngineRulesetUnavailableException`. `load`가 `INVALID_ARGUMENT`를 받으면 `IllegalStateException`.
- `ErrorCode`: `UNPROCESSABLE(422 — 프로젝트 Spring 버전의 `HttpStatus` 상수, "처리할 수 없는 요청입니다.")`, `SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "잠시 후 다시 시도해 주세요.")` (`docs/api-convention.md` 오류 표에도 추가)
- 가짜 엔진(테스트 전용, static 상태는 테스트마다 `reset()`):
  - `compileRules`: 원문에 `SYNTAX_ERROR`가 있으면 `ok=false, error_message="line 1: syntax error"`; 아니면 정규식 `rule\s+(\w+)`로 이름, 바로 뒤 `severity\s*=\s*"(\w+)"`로 심각도(없으면 MEDIUM). 룰 0개도 `ok=true, rules=[]`로 돌려준다(0개 거부는 서버 책임).
  - `loadRuleset`: `static volatile String loadedKey`(초기 `""`)와 `static AtomicInteger loadCount` 갱신.
  - `analyze`: metadata의 `ruleset_key`가 비어 있지 않고 `loadedKey`와 다르면 `onError(FAILED_PRECONDITION)`. 응답 `rulesets_version`에 마지막 적재 버전.

- [ ] **Step 1: 실패하는 테스트** — `RulesetSnapshot` 단위 테스트 `키는_꺼진_룰_순서와_무관하다`(`["b","a"]`와 `["a","b"]` 키가 같고 `[]`과 다름, 형식 `^3:[0-9a-f]{16}$`); 포트 테스트 `컴파일_결과를_도메인_값으로_바꾼다`, `적재하면_가짜_엔진의_키가_바뀐다`.
- [ ] **Step 2: 실패 확인** — `./gradlew test --tests '*RulesetSnapshot*' --tests '*GrpcEngineRulesetPortImplTest'` → FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — 같은 명령 + 기존 `SubmitFileGrpcIntegrationTest` → PASS
- [ ] **Step 5: Commit** — `git commit -m "Feat: 탐지 엔진 룰셋 포트와 422/503 에러 코드"`

### Task 5: 업로드 API

**Files:**
- Create: `.../ruleset/application/UploadRulesetUseCase.java`, `InvalidRulesetException.java`
- Modify: `.../ruleset/interfaces/RulesetController.java`
- Modify: `.../audit/domain/AuditAction.java` (`UPLOAD_RULESET` 추가 — `audit_logs.action`은 VARCHAR라 마이그레이션 불필요)
- Modify: `api-server/src/main/resources/application.yml` (`spring.servlet.multipart.max-file-size: 1MB`, `max-request-size: 2MB`)
- Modify: `.../common/GlobalExceptionHandler.java` (`MaxUploadSizeExceededException` → 400 `INVALID_INPUT`)
- Modify: `infra/nginx/nginx.conf` (`location /api/`에 `client_max_body_size 2m;`)
- Test: `api-server/src/test/java/com/teecherteamc/platform/RulesetUploadRestIntegrationTest.java`

**Interfaces:**
- Consumes: Task 3 리포지토리, Task 4 `EngineRulesetPort.compile`
- `UploadRulesetUseCase.upload(Command) -> Ruleset`, `record Command(String filename, byte[] content, UUID adminId, String ipAddress)`
- 순서: 확장자·크기·빈 파일 검사(→ `InvalidRulesetException`, 400) → UTF-8 디코드 → `compile` (`EngineRulesetUnavailableException` → 503) → `ok=false`면 엔진 메시지로 422 → 룰 0개면 422 `"룰이 하나도 없습니다."` → `@Transactional` 안에서 버전 = `findMaxVersion()+1`(동시 업로드는 PK 충돌 → 409 `CONFLICT`), `Ruleset.upload`, 룰 저장(심각도 정규화), 감사 로그 `UPLOAD_RULESET`, target `RULESET`, targetId = 버전 문자열, detail `{filename, ruleCount}`.
- 엔진 호출은 트랜잭션 밖에서 먼저 한다(DB 커넥션을 잡은 채 기다리지 않게).
- `POST /api/admin/rulesets` (multipart `file`) → 201 `RulesetItem`

- [ ] **Step 1: 실패하는 통합 테스트** (multipart는 `AdminApiClient`에 헬퍼가 없으면 `LinkedMultiValueMap` + `ByteArrayResource(filename 오버라이드)`로)
  - `올린_파일은_대기_버전이_되고_룰은_모두_켜져_있다`: 룰 2개 원문 → 201, version == 최대+1, isActive == false, ruleCount == 2; DB에서 enabled 전부 true; 감사 로그 `UPLOAD_RULESET` 1건
  - `문법_오류는_422이고_엔진_메시지를_준다`: `SYNTAX_ERROR` 포함 → 422, message에 `"syntax error"`
  - `룰이_없는_파일은_422`
  - `yar가_아닌_파일과_빈_파일은_400`
  - `1MiB를_넘으면_400` (1,048,577 바이트)
  - `엔진에_닿지_않으면_503`: 가짜 엔진에 `failNextCall` 플래그(`UNAVAILABLE`)를 두고 사용
  - 어떤 실패 케이스에서도 `yara_rulesets` 행 수가 늘지 않음
- [ ] **Step 2: 실패 확인** — `./gradlew test --tests '*RulesetUploadRestIntegrationTest'` → FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS
- [ ] **Step 5: Commit** — `git commit -m "Feat: YARA 룰셋 업로드 API"`

### Task 6: 활성화·룰 사용 변경 API와 판정 stale 처리

**Files:**
- Create: `.../ruleset/application/ActivateRulesetUseCase.java`, `ChangeYaraRuleEnabledUseCase.java`, `ActiveRulesetService.java`, `ActiveRulesetPusher.java`
- Create: `.../ruleset/domain/RulesetDetectionChanged.java` (`record RulesetDetectionChanged(int version)`)
- Create: `.../verdict/application/RulesetDetectionChangedListener.java`
- Modify: `.../verdict/domain/FileVerdictRepository.java` + 구현 (`int markEngineVerdictsStale()`)
- Modify: `.../ruleset/interfaces/RulesetController.java` (+ `UpdateYaraRuleRequest(@NotNull Boolean enabled)`)
- Test: `RulesetActivateRestIntegrationTest.java`, `YaraRuleToggleRestIntegrationTest.java`

**Interfaces:**
- `ActiveRulesetService` (public, Task 7이 사용): `RulesetSnapshot activeSnapshot()` (활성 버전 원문 + `findDisabledNames`; 활성 버전이 없으면 `IllegalStateException`), `void pushActive()` (= `engine.load(activeSnapshot())`).
- `ActivateRulesetUseCase.activate(int version, UUID adminId, String ip) -> Ruleset`: 없음/v0 → `RulesetNotFoundException`(404). 이미 활성이면 아무것도 바꾸지 않고 반환(이벤트·감사 로그 없음). 아니면 `deactivateAll()` → 대상 `activate` → 이벤트 `RulesetDetectionChanged` → 감사 `ACTIVATE_RULESET`(detail `{previousVersion}`).
- `ChangeYaraRuleEnabledUseCase.change(int version, UUID ruleId, boolean enabled, UUID adminId, String ip) -> YaraRule`: 없는 룰 404. 값이 같으면 그대로 반환. 바뀌면 저장 + 감사 `ENABLE_YARA_RULE`/`DISABLE_YARA_RULE`(targetId = ruleId, detail `{version, ruleName}`), 그리고 **그 버전이 활성일 때만** `RulesetDetectionChanged` 발행.
- `RulesetDetectionChangedListener` (`@EventListener`, 발행 트랜잭션 안): `fileVerdicts.markEngineVerdictsStale()` — `UPDATE file_verdicts SET is_stale = true WHERE verdict_source = 'ENGINE' AND is_stale = false`.
- `ActiveRulesetPusher` (`@TransactionalEventListener(phase = AFTER_COMMIT)`): `activeRulesetService.pushActive()`, 어떤 예외든 잡아서 WARN 로그만.
- `POST /api/admin/rulesets/{version}/activate` → 200 `RulesetItem`; `PATCH /api/admin/rulesets/{version}/rules/{ruleId}` body `{"enabled": false}` → 200 `YaraRuleItem` (`enabled` 누락 → 400).

- [ ] **Step 1: 실패하는 통합 테스트**
  - `활성화하면_이전_버전은_내려가고_ENGINE_판정만_stale이_된다`: ENGINE·MANUAL 판정 하나씩 시드, v2 업로드(Task 5 유스케이스로) 후 활성화 → 활성 버전 [2], ENGINE 행 stale=true, MANUAL 행 stale=false, 감사 `ACTIVATE_RULESET`, 가짜 엔진 `loadedKey == 활성 스냅샷 key()`
  - `이미_활성인_버전을_다시_활성화하면_200이고_아무것도_바뀌지_않는다`: stale 그대로 false, 감사 로그 0건 증가
  - `이전_버전을_다시_활성화해_되돌릴_수_있다`
  - `없는_버전과_v0_활성화는_404`
  - `활성_버전의_룰을_끄면_stale이_되고_엔진_키가_바뀐다`
  - `대기_버전의_룰을_끄면_stale도_적재도_없다`: `loadCount` 변화 없음
  - `enabled가_없으면_400`, `다른_버전의_ruleId면_404`
  - `엔진_적재가_실패해도_활성화는_200`: 가짜 엔진 `failNextCall`
- [ ] **Step 2: 실패 확인** — `./gradlew test --tests '*RulesetActivateRestIntegrationTest' --tests '*YaraRuleToggleRestIntegrationTest'` → FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS
- [ ] **Step 5: Commit** — `git commit -m "Feat: 룰셋 활성화·룰 사용 변경 API와 엔진 판정 stale 처리"`

### Task 7: 검사 경로의 룰셋 동기화

**Files:**
- Modify: `.../verdict/infrastructure/GrpcEngineAnalysisPortImpl.java`
- Test: `api-server/src/test/java/com/teecherteamc/platform/EngineRulesetSyncIntegrationTest.java`

**Interfaces:**
- Consumes: Task 6 `ActiveRulesetService.activeSnapshot()`, Task 4 `EngineRulesetPort.load`
- `analyze(Path, long)` 시그니처는 그대로. 메타데이터에 `ruleset_key = activeSnapshot().key()`. `ExecutionException`의 원인이 `StatusRuntimeException`이고 코드가 `FAILED_PRECONDITION`이면: `engine.load(snapshot)` 후 같은 스풀 파일로 한 번 더 보낸다. 두 번째도 실패하면(또는 `load`가 실패하면) 기존과 같이 `EngineUnavailableException`. 다른 상태 코드에는 재전송하지 않는다.

- [ ] **Step 1: 실패하는 통합 테스트** (`SubmitFileGrpcIntegrationTest` 하네스 재사용)
  - `엔진이_다른_룰셋을_들고_있으면_적재하고_같은_파일을_다시_검사한다`: 가짜 엔진 `reset()`(loadedKey="") → SubmitFile(MALICIOUS_MARKER) → verdict MALICIOUS, `loadCount == 1`, `analyses.rulesets_version == 활성 버전`
  - `키가_맞으면_적재하지_않는다`: 한 번 더 SubmitFile(다른 내용) → `loadCount` 그대로
  - `엔진이_UNAVAILABLE이면_재전송하지_않는다`: `CRASH_MARKER` → 기존처럼 실패 기록, `loadCount` 0
- [ ] **Step 2: 실패 확인** — `./gradlew test --tests '*EngineRulesetSyncIntegrationTest'` → FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 전체 서버 테스트** — `make test-api` → PASS (기존 SubmitFile·CheckHash 테스트 포함)
- [ ] **Step 5: Commit** — `git commit -m "Feat: 검사 요청에 룰셋 키를 실어 엔진 룰셋을 맞춘다"`

### Task 8: 콘솔 — 룰셋 화면

**Files:**
- Modify: `console/src/lib/api/client.ts` (본문이 문자열일 때만 `Content-Type: application/json`; `FormData`면 브라우저에 맡김)
- Rewrite: `console/src/lib/api/rulesets.ts`, `console/src/mocks/data/rulesets.ts`, `console/src/mocks/handlers/rulesets.ts`
- Create: `console/src/lib/rulesets.ts`, `console/src/lib/rulesets.test.ts`
- Rewrite: `console/src/pages/RulesetsPage.tsx`; Create: `console/src/pages/RulesetsPage.test.tsx`
- Modify: `console/src/index.css` (`ruleset-` 접두사 클래스만 추가, 기존 `events-*`·`whitelist-*` 재사용)

**Interfaces:**
- 타입: `RulesetItem { version: number; ruleCount: number; disabledRuleCount: number; isActive: boolean; createdAt: string; activatedAt: string | null }`, `YaraRuleSeverity = 'LOW'|'MEDIUM'|'HIGH'|'CRITICAL'`, `YaraRuleItem { ruleId: string; ruleName: string; severity: YaraRuleSeverity; enabled: boolean }`
- API: `getRulesets(query?: { page?: number; size?: number }) -> Promise<PageResponse<RulesetItem>>`, `getRulesetRules(version: number) -> Promise<PageResponse<YaraRuleItem>>`, `uploadRuleset(file: File) -> Promise<RulesetItem>`, `activateRuleset(version: number) -> Promise<RulesetItem>`, `updateYaraRuleEnabled(version: number, ruleId: string, enabled: boolean) -> Promise<YaraRuleItem>`
- `lib/rulesets.ts`: `rulesetStatus(item: RulesetItem): '적용 중' | '대기' | '이전'` (활성 → 적용 중, `activatedAt` 있음 → 이전, 없음 → 대기), `validateRulesetFile(file: { name: string; size: number }): string` (빈 문자열 = 통과; `.yar` 아님 / 0바이트 / 1,048,576 초과 각각 메시지), `rulesetErrorMessage(cause: unknown): string` (400·422는 서버 `message`를 그대로, 503 `'탐지 엔진에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'`, 404 `'룰셋을 찾을 수 없습니다. 목록을 새로고침해 주세요.'`, 그 밖 `'요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'`), `disableConfirmMessage(rules: YaraRuleItem[], target: YaraRuleItem, isActiveVersion: boolean): string | null` (대기 버전 → null; 활성 버전에서 마지막 켜진 룰을 끄면 `'마지막 룰을 끄면 YARA 탐지가 꺼집니다. 계속할까요?'`, 그 밖 `'적용 중인 룰셋이 바뀌어 기존 엔진 판정이 다시 검사됩니다. 계속할까요?'`)
- 화면(화이트리스트 레이아웃 따름 — `page events-page`, `page-heading`의 `whitelist-title` 크기 제목, `events-panel`/`events-panel-heading`/`events-table`):
  - 제목 "YARA 룰셋", 오른쪽 ".yar 업로드" 버튼(숨김 `<input type="file" accept=".yar">`).
  - 2단: 왼쪽 버전 목록(버튼 리스트: `v{version}`, 상태, `룰 {ruleCount}개`, 올린 시각; 선택 표시), 오른쪽 선택 버전 패널(제목 `v{n} · 룰 {n}개`, 활성 아니면 "이 버전 활성화" 버튼, 활성이면 "적용 중" 배지; 표 열: 룰 이름, 심각도, 사용 토글 `role="switch" aria-checked`).
  - 첫 로드 시 활성 버전 선택. 업로드 성공 시 새 버전 선택. 활성화 전 `window.confirm('v{n}을 적용하면 기존 엔진 판정이 다시 검사됩니다. 계속할까요?')`. 룰 끄기 전 `disableConfirmMessage`가 null이 아니면 confirm.
  - 오류는 `rulesetErrorMessage`를 `events-filter-error` 스타일로 표시.
  - 테스트용으로 `RulesetRuleRow({ rule, isBusy, onToggle })`와 `RulesetVersionItem({ item, selected, onSelect })`를 export.
- mock: v3(활성, 룰 3개 중 1개 꺼짐), v2(이전), v4(대기) 데이터; 5개 엔드포인트 핸들러(업로드는 파일 이름에 `error`가 있으면 422 `{ message: 'line 3: syntax error' }`).

- [ ] **Step 1: 실패하는 테스트** — `lib/rulesets.test.ts`: `rulesetStatus` 세 경우; `validateRulesetFile`(`a.yar` 10B 통과, `a.txt`, 0B, 1,048,577B 거부); `rulesetErrorMessage`(422 메시지 전달, 503 문구); `disableConfirmMessage`(대기 null, 마지막 룰 경고, 일반 경고). `RulesetsPage.test.tsx`(`WhitelistPage.test.tsx`처럼 `renderToStaticMarkup`): 켜진 룰 행에 `aria-checked="true"`, 심각도 텍스트; 활성 버전 항목에 `적용 중`.
- [ ] **Step 2: 실패 확인** — `cd console && npx vitest run src/lib/rulesets.test.ts src/pages/RulesetsPage.test.tsx` → FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — `npm run lint && npx vitest run && npm run build` → PASS
- [ ] **Step 5: 화면 확인** — `VITE_API_MODE=mock`, `VITE_AUTH_MODE=enabled`로 dev 서버를 띄워 로그인 후 `/rulesets`에서 업로드(정상·`error` 이름), 활성화, 토글을 스크린샷으로 확인
- [ ] **Step 6: Commit** — `git commit -m "Feat: YARA 룰셋 관리 화면 구현 및 실제 API 연동"`
