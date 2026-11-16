# 룰셋 업로드 상한 32MiB Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 32MiB 이하 YARA 룰셋이 콘솔 → nginx → api-server → 엔진까지 업로드·활성화되게 한다.

**Architecture:** 상한을 api-server 설정값(`ruleset.max-source-bytes`)과 엔진 환경변수(`ENGINE_MAX_RULES_BYTES`)로 바꾸고, 기본값을 32MiB로 둔다. 나머지 층(multipart, nginx, 콘솔, 엔진 gRPC 수신 크기, 엔진 호출 시간 제한)은 이 값에 맞춘다.

**Tech Stack:** Spring Boot(Java 21), grpc-python, nginx, React/Vitest

**Spec:** `docs/superpowers/specs/2026-11-16-ruleset-size-limit-design.md`

## Global Constraints
- 원문 상한 기본값 `33554432`(32MiB). 엔진 gRPC 수신 크기 = 원문 상한 + `1048576`.
- Spring multipart: `max-file-size: 32MB`, `max-request-size: 33MB`.
- nginx: `/api/`의 `client_max_body_size 2m`은 그대로 둔다. `location = /api/admin/rulesets`만 `33m`.
- 엔진 룰셋 RPC 시간 제한 기본 60초(`ruleset.engine-timeout: 60s`).
- 사용자에게 보이는 문구: "파일은 32MiB 이하여야 합니다." (콘솔: "파일이 너무 큽니다. 32MB 이하의 룰셋만 올릴 수 있습니다.")
- 룰셋 목록 조회의 `source` 로딩은 건드리지 않는다(비목표).

## Review Focus
1. nginx `location = /api/admin/rulesets`가 `/api/` 블록의 proxy 설정(헤더, SSE 버퍼링 off 등)을 빠짐없이 복사하는가. 빠지면 감사 로그 IP(`X-Forwarded-For`)가 틀어진다 → Task 3 `nginx -t`와 diff 검토.
2. 상한 경계: 정확히 32MiB는 통과하고 32MiB+1은 400 → Task 2 테스트.
3. 엔진이 상한 근처 원문을 gRPC로 받을 때 4MiB 기본 제한에 걸리지 않는가 → Task 1 테스트(5MiB 원문 컴파일 성공).
4. 설정값을 바꿨을 때 에러 문구도 따라가는가 → Task 2(문구를 설정값에서 만든다).
5. 실제 YARA Forge core·full이 운영 구성에서 올라가는가 → Task 4.

---

### Task 1: 엔진 원문 상한과 gRPC 수신 크기

**Files:**
- Modify: `detection-engine/src/detection_engine/analyzer.py` (`MAX_SOURCE_BYTES`)
- Modify: `detection-engine/src/detection_engine/server.py` (`create_server`의 `grpc.server(...)`)
- Test: `detection-engine/tests/test_ruleset.py`

**Interfaces:**
- Produces: `MAX_SOURCE_BYTES = int(os.environ.get("ENGINE_MAX_RULES_BYTES", str(32 * 1024 * 1024)))`. `create_server`는 `options=[("grpc.max_receive_message_length", MAX_SOURCE_BYTES + 1024 * 1024)]`로 서버를 만든다.

- [ ] **Step 1: 실패하는 테스트 작성** (`test_ruleset.py`)
  - 기존 `test_1MiB를_넘는_원문은_거부한다`, `..._적재도_거부한다`의 크기를 `MAX_SOURCE_BYTES + 1`로 바꾸고, 이름을 `test_상한을_넘는_원문은_...`으로 바꾼다.
  - 새 테스트 `test_5MiB_원문도_gRPC로_받아_컴파일한다`: 룰 1개 + `"//" + "x" * (5 * 1024 * 1024)` 주석 원문으로 `CompileRules` → `response.ok is True`, 룰 1개.
- [ ] **Step 2:** `make test-engine` → 새 테스트 FAIL (RESOURCE_EXHAUSTED 또는 상한 초과).
- [ ] **Step 3:** 위 Interfaces대로 구현한다.
- [ ] **Step 4:** `make test-engine` → 전체 PASS.
- [ ] **Step 5:** 커밋 `feat(engine): 룰셋 원문 상한을 32MiB 설정값으로, gRPC 수신 크기도 맞춤`

### Task 2: api-server 상한 설정값과 시간 제한

**Files:**
- Modify: `api-server/src/main/resources/application.yml` (multipart, `ruleset.max-source-bytes`, `ruleset.engine-timeout`)
- Modify: `api-server/src/main/java/com/teecherteamc/platform/ruleset/application/UploadRulesetUseCase.java` (`MAX_BYTES` 상수 → 설정값)
- Modify: `api-server/src/main/java/com/teecherteamc/platform/common/GlobalExceptionHandler.java` (`handleUploadTooLarge` 문구)
- Modify: `api-server/src/main/java/com/teecherteamc/platform/ruleset/infrastructure/GrpcEngineRulesetPortImpl.java` (`TIMEOUT_SECONDS` → 설정값)
- Test: `api-server/src/test/java/com/teecherteamc/platform/RulesetUploadRestIntegrationTest.java`

**Interfaces:**
- Produces: 설정 `ruleset.max-source-bytes`(기본 `33554432`), `ruleset.engine-timeout`(기본 `60s`, `Duration`). 크기 초과 문구는 한 곳(예: `RulesetLimits.tooLargeMessage()` 또는 UseCase의 정적 헬퍼)에서 만들어 UseCase와 `GlobalExceptionHandler`가 같이 쓴다. 기존 패턴(`@Value`)을 따른다.

- [ ] **Step 1: 실패하는 테스트 작성** (`RulesetUploadRestIntegrationTest`)
  - `일_MiB를_넘으면_400`을 `이_MiB_룰셋도_올라간다`로 바꾼다: 2MiB(룰 1개 + 공백 채움) 업로드 → 201, 버전 1개 증가.
  - `상한_32MiB는_통과하고_넘으면_400`: 정확히 `33554432`바이트 → 201. `33554433`바이트 → 400, 메시지 `"파일은 32MiB 이하여야 합니다."`, 버전 수 그대로.
- [ ] **Step 2:** 해당 클래스만 실행 → FAIL (지금은 2MiB도 400).
- [ ] **Step 3:** 구현한다. multipart는 `max-file-size: 32MB`, `max-request-size: 33MB`.
- [ ] **Step 4:** `make test-api` 전체 → PASS. 화이트리스트 만료 테스트는 알려진 불안정 테스트이니, 실패하면 단독으로 다시 돌린다.
- [ ] **Step 5:** 커밋 `feat(ruleset): 업로드 상한과 엔진 호출 시간 제한을 설정값으로 (기본 32MiB, 60초)`

### Task 3: nginx와 콘솔

**Files:**
- Modify: `infra/nginx/nginx.conf` (`location = /api/admin/rulesets` 추가, `/api/` 주석의 "최대 1MiB" 정정)
- Modify: `console/src/lib/rulesets.ts`, `console/src/lib/rulesets.test.ts`

- [ ] **Step 1: 콘솔 테스트 수정**: `33_554_432` → `''`, `33_554_433` → 에러 문구 `'파일이 너무 큽니다. 32MB 이하의 룰셋만 올릴 수 있습니다.'`. 테스트 이름의 1MiB → 32MiB.
- [ ] **Step 2:** `make test-console` → FAIL.
- [ ] **Step 3:** `MAX_RULESET_BYTES = 33_554_432`와 문구를 고친다.
- [ ] **Step 4:** nginx에 `location = /api/admin/rulesets { client_max_body_size 33m; ... }`를 추가한다. `/api/` 블록의 proxy 지시문을 그대로 복사한다. 그다음 `docker run --rm -v $PWD/infra/nginx/nginx.conf:/etc/nginx/nginx.conf:ro nginx:1.29-alpine nginx -t`를 돌린다. upstream 이름 해석 때문에 실패하면 `docker compose ... config`와 Task 4 실측으로 대신한다.
- [ ] **Step 5:** `make test-console` → PASS. 커밋 `feat: 콘솔과 nginx의 룰셋 업로드 상한을 32MiB로`

### Task 4: 실제 룰셋으로 확인

**Files:** 없음 (확인만)

- [ ] **Step 1:** 별도 compose 프로젝트로 스택을 띄운다(`-p ruleset-check`, 개발 `platform` 프로젝트와 볼륨은 건드리지 않음).
- [ ] **Step 2:** nginx를 거쳐 관리 API로 YARA Forge `20261115` core(7.7MB)를 업로드하고 활성화한다 → 201, 활성화 성공. 엔진 로그에서 컴파일 시간을 확인한다.
- [ ] **Step 3:** full(19.4MB)도 같은 순서로 올리고 활성화한다. 활성화 순간 엔진 메모리 최대치를 `docker stats`로 기록한다.
- [ ] **Step 4:** 33MiB 넘는 파일 → 400 또는 413(nginx)을 확인한다.
- [ ] **Step 5:** 스택을 정리하고(`-p ruleset-check down -v`), 결과를 PR 설명에 붙인다.
