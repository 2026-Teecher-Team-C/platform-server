# 판정 근거(VerdictEvidence) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** CheckHash·SubmitFile 차단 응답에 구조화된 판정 근거를 싣고, 에이전트 차단 페이지가 그것을 상용 서비스처럼 말로 보여 준다.

**Architecture:** proto에 `VerdictEvidence`를 추가(필드 추가만)하고, 서버는 `verdict.domain.VerdictEvidence` 값 객체를 결과 레코드에 실어 gRPC 응답으로 바꾼다. 에이전트는 `Outcome`에 근거를 담아 `block_page.render`가 근거 종류별 문구와 접힌 "자세히"를 그린다.

**Tech Stack:** Java 21 / Spring Boot / grpc-java (api-server), Python 3.12 / mitmproxy (platform-agent)

**Spec:** `project/docs/superpowers/specs/2026-11-25-block-page-evidence-design.md`, 조사 `project/docs/research/2026-11-25-block-page-benchmark.md`

## Global Constraints

- proto는 필드·메시지 추가만. 기존 필드 번호·타입 변경 금지. 머지 뒤 태그 `proto-v0.4.0`
- 기존 `reason`, `matched_rules`는 지금 값 그대로 채운다(구버전 호환)
- 근거는 decision이 BLOCK일 때만 채운다. ALLOW·UNKNOWN이면 비운다
- 패밀리 규칙: 사유에 `": "`가 있으면 그 뒤, 없으면 사유 전체. 결과가 출처 이름뿐(`MalwareBazaar`)이거나 비면 `""`
- 차단 페이지: 숫자(거리·SHA·심각도)는 `<details>`("자세히") 안에만. JS·외부 리소스 없음, 모든 값 `html.escape`, 파일명·URL 넣지 않음
- YARA 룰은 페이지에 최대 5개, 나머지는 "외 N개"
- api-server 코드 스타일: 우테코(4칸, 120자). 테스트 이름 한글 가능

## Review Focus

1. 유사 샘플이 블랙리스트에서 해제됐거나 사유가 없는 경우 → label·family 빈 문자열, 차단 응답은 정상(Task 3 테스트)
2. 근거 없는 구버전 서버 응답(evidence 미설정) → 에이전트 페이지는 지금처럼 사유 한 줄(Task 5 테스트)
3. YARA 룰 이름·사유에 `<script>` → 이스케이프(Task 5 테스트)
4. 엔진 분석 실패(크래시·타임아웃) 차단 → 근거 kind는 UNSPECIFIED, reason 유지(Task 4 테스트)
5. 룰 7개 매칭 → 페이지는 5개와 "외 2개"(Task 5 테스트)

---

### Task 1: proto 계약

**Files:**
- Modify: `proto/teecher/verdict/v1/verdict.proto`
- Test: `api-server/src/test/java/com/teecherteamc/platform/ProtoContractTest.java`

**Interfaces:**
- Produces: 설계 3-1의 `EvidenceKind`, `MatchedRule{name=1, severity=2}`, `VerdictEvidence{kind=1, label=2, family=3, tlsh_distance=4, matched_sample_sha256=5, rules=6, previous_verdict=7}`, `CheckHashResponse.evidence = 4`, `SubmitFileResponse.evidence = 5`

- [ ] Step 1: `ProtoContractTest`에 `근거_필드_번호가_고정되어_있다` 추가 — 디스크립터로 `CheckHashResponse.evidence` 번호 4, `SubmitFileResponse.evidence` 번호 5, `VerdictEvidence` 필드 7개의 이름·번호, `EvidenceKind` 값 0~4를 검사
- [ ] Step 2: `make test-api` 해당 테스트 FAIL 확인(심볼 없음으로 컴파일 실패)
- [ ] Step 3: proto에 정의 추가(설계 3-1 그대로, 주석 포함)
- [ ] Step 4: 테스트 PASS
- [ ] Step 5: 커밋 `feat(proto): 판정 근거 VerdictEvidence 추가`

### Task 2: 근거 값 객체

**Files:**
- Create: `api-server/src/main/java/com/teecherteamc/platform/verdict/domain/VerdictEvidence.java`
- Test: `api-server/src/test/java/com/teecherteamc/platform/verdict/domain/VerdictEvidenceTest.java`

**Interfaces:**
- Produces: `record VerdictEvidence(Kind kind, String label, String family, Integer tlshDistance, String matchedSampleSha256, List<Rule> rules, String previousVerdict)`, `enum Kind { NONE, BLACKLIST, SIMILARITY, YARA, CACHE }`, `record Rule(String name, String severity)`, 정적 팩토리 `none()`, `blacklist(String label)`, `similarity(int distance, String sampleSha256, String sampleLabel)`, `yara(List<Rule> rules)`, `cache(String previousVerdict)`, `static String familyOf(String label)`

- [ ] Step 1: 테스트 — `familyOf("MalwareBazaar: Mirai")=="Mirai"`, `familyOf("Mirai")=="Mirai"`, `familyOf("MalwareBazaar")==""`, `familyOf(null)==""`, `familyOf("MalwareBazaar: ")==""`; `similarity(12, sha, null)`의 label·family가 `""`; `blacklist("MalwareBazaar: Mirai").family()=="Mirai"`
- [ ] Step 2: FAIL 확인
- [ ] Step 3: 구현. null 문자열은 `""`로, rules는 불변 리스트. "출처 이름뿐" 판정은 `": "`가 없고 값이 `MalwareBazaar`와 같을 때(출처 목록 상수 `Set.of("MalwareBazaar")`)
- [ ] Step 4: PASS
- [ ] Step 5: 커밋 `feat(verdict): 판정 근거 값 객체`

### Task 3: CheckHash 근거

**Files:**
- Modify: `lookup/application/CheckHashResult.java`, `lookup/application/CheckHashUseCase.java`, `verdict/application/SimilarityDecisions.java`, `verdict/interfaces/VerdictGrpcService.java`
- Test: `CheckHashGrpcIntegrationTest.java`, `TlshSimilarityGrpcIntegrationTest.java`

**Interfaces:**
- Consumes: Task 2
- Produces: `CheckHashResult(String decision, String source, String reason, VerdictEvidence evidence)` + 기존 3인자 생성자(evidence = `none()`); `SimilarityDecisions.sampleLabel(String sampleSha256) -> String`(활성 블랙리스트 사유, 없으면 null); `VerdictGrpcService.toProtoEvidence(VerdictEvidence) -> teecher...VerdictEvidence`(NONE이면 기본 인스턴스)

- [ ] Step 1: 테스트
  - 블랙리스트 해시 CheckHash → `evidence.kind == EVIDENCE_KIND_BLACKLIST`, label = 등록 사유, family
  - TLSH 차단(거리 ≤30) → `SIMILARITY`, `tlsh_distance` = 응답 reason의 거리와 같음, `matched_sample_sha256` = 비교 샘플, label·family = 그 샘플의 블랙리스트 사유
  - 판정 이력 MALICIOUS → `CACHE`, `previous_verdict == "MALICIOUS"`
  - UNKNOWN·ALLOW → `hasEvidence() == false`
  - 비교 샘플 사유가 없을 때(Review Focus 1) → label·family `""`, decision BLOCK
  - `reason`은 기존 값 그대로("TLSH distance: N")
- [ ] Step 2: FAIL 확인
- [ ] Step 3: 구현 — `fromLists` 블랙리스트 → `blacklist(reason)`, TLSH BLOCK → `similarity(distance, assessment.sample(), similarity.sampleLabel(assessment.sample()))`, `fromCache` BLOCK → `cache(verdict)`, exactOverride 경로 → `blacklist(reason)`. gRPC 응답에 `setEvidence`는 BLOCK이고 kind≠NONE일 때만
- [ ] Step 4: `make test-api` 전체 PASS
- [ ] Step 5: 커밋 `feat(lookup): CheckHash 차단 응답에 판정 근거`

### Task 4: SubmitFile 근거

**Files:**
- Modify: `verdict/application/SubmitFileResult.java`, `verdict/application/SubmitFileUseCase.java`, `verdict/application/SimilarityDecisions.java`(exactOverride), `verdict/interfaces/VerdictGrpcService.java`
- Test: `SubmitFileGrpcIntegrationTest.java`

**Interfaces:**
- Consumes: Task 2, Task 3의 `toProtoEvidence`, `sampleLabel`
- Produces: `SubmitFileResult(String decision, String source, String reason, List<String> matchedRules, VerdictEvidence evidence)` + 기존 4인자 생성자(evidence = `none()`)

- [ ] Step 1: 테스트
  - YARA MALICIOUS → `YARA`, rules = 엔진 매칭 이름·심각도(순서 유지), `matched_rules` 기존대로
  - 업로드 후 TLSH 재비교 차단 → `SIMILARITY`(Task 3과 같은 값)
  - 블랙리스트 exactOverride → `BLACKLIST`
  - 캐시 MALICIOUS → `CACHE`
  - 엔진 실패(크래시) BLOCK → 근거 없음, reason "엔진 분석 실패: …" 유지(Review Focus 4)
- [ ] Step 2: FAIL 확인
- [ ] Step 3: 구현 — `toResult`에서 `outcome.matches()`를 `Rule(name, severity)`로
- [ ] Step 4: `make test-api` 전체 PASS
- [ ] Step 5: 커밋 `feat(verdict): SubmitFile 차단 응답에 판정 근거`

### Task 5: 에이전트 차단 페이지 근거 표시 (platform-agent, `feat/block-page` 위)

**Files:**
- Modify: `proto` 서브모듈(이 브랜치의 Task 1 커밋을 가리키게, 태그 전 임시), `src/agent/addon.py`(`Outcome`에 `evidence`, CheckHash·SubmitFile 응답에서 채움), `src/agent/block_page.py`
- Test: `tests/test_block_page.py`, `tests/test_addon.py`

**Interfaces:**
- Consumes: Task 1의 proto
- Produces: `Outcome.evidence: verdict_pb2.VerdictEvidence | None`, `block_page.render(*, fail_close, reason, event_id, evidence=None)`, `block_page.plain_lines(evidence) -> list[str]`

- [ ] Step 1: 테스트 — 설계 3-3 표의 근거 종류별: 위협 이름 줄, 설명 문장(설계 문구 그대로), `<details>` 안에 거리·샘플 SHA 전체·룰 심각도; 근거 없음은 지금 화면 그대로(Review Focus 2); `<script>` 룰 이름 이스케이프(3); 룰 7개 → 5개 + "외 2개"(5); text/plain에 위협 이름과 숫자 한두 줄; 숫자는 `<details>` 밖 본문에 나오지 않음
- [ ] Step 2: FAIL 확인
- [ ] Step 3: 구현 — 문구는 설계 3-3 그대로
- [ ] Step 4: `uv run --group dev pytest`, ruff check·format PASS. 근거 종류 4개 + 근거 없음 미리보기 HTML·스크린샷(밝은/어두운)
- [ ] Step 5: 커밋 `feat: 차단 페이지에 판정 근거 표시`
