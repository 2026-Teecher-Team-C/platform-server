# 블랙리스트 일괄 가져오기 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** MalwareBazaar 전체 덤프(117만 해시, TLSH 81만)를 1,000건 묶음 API로 블랙리스트·TLSH 지문에 넣는다.

**Architecture:** hashlist 컨텍스트에 묶음 유스케이스와 JDBC 일괄 쓰기 포트를 둔다. TLSH 지문은 verdict 컨텍스트의 일괄 저장 서비스로 넣는다. 블룸은 커밋 뒤 파이프라인 한 번으로 추가한다. 스크립트(표준 라이브러리 Python)가 덤프를 받아 묶음을 보낸다.

**Tech Stack:** Spring Boot / JdbcTemplate / Testcontainers, Redis, Python 3 표준 라이브러리 + unittest

**Spec:** `docs/superpowers/specs/2026-11-16-blacklist-bulk-import-design.md`

## Global Constraints
- 엔드포인트 `POST /api/admin/blacklist/import`. 묶음 1~1,000건, 넘으면 400.
- 응답 필드: `received, added, skippedActive, skippedInactive, skippedWhitelisted, invalid, fingerprintsAdded, fingerprintsSkipped`.
- 블랙리스트 `source=IMPORT`, `severity=HIGH`, `reason="{source}: {label}"`(label이 없거나 `n/a`면 `"{source}"`).
- 지문 `provenance=ADMIN_IMPORT`, `provenance_detail=source`. TLSH는 `^T1[0-9A-F]{70}$`만.
- 잠금 순서: 정렬한 SHA마다 eligibility → corpus.
- 감사 로그 묶음당 1건, `IMPORT_BLACKLIST`.

## Review Focus
1. 한 건짜리 등록과 묶음 가져오기를 동시에 돌릴 때 교착 → Task 3 동시성 테스트.
2. 해제된 항목을 되살리지 않는가 → Task 3.
3. VERIFIED_UPLOAD 지문을 덮어쓰지 않는가 → Task 3.
4. 117만 건 리허설: 소요 시간, 잠금 표 한도, DB 크기 → Task 5.
5. 지문 81만 건에서 CheckHash TLSH 비교 지연(측정 첫 숫자) → Task 5.

---

### Task 1: 블룸 일괄 추가
**Files:** `lookup/domain/BloomFilterPort.java`, `lookup/infrastructure/RedisBloomFilterPortImpl.java`, `hashlist/application/BloomAfterCommit.java`. Test: `CheckHashGrpcIntegrationTest`(또는 새 `RedisBloomFilterPortImplIntegrationTest`).
**Produces:** `void addAll(Collection<String> sha256s)` — 파이프라인 한 번. 실패는 `add`와 같이 로그만 남기고 삼킨다. `BloomAfterCommit.addAll(BloomFilterPort, Collection<String>)`.
- [ ] RED: `addAll`로 3건을 넣으면 `mightContain`이 셋 다 true이고, 빈 목록이면 아무 일도 없다.
- [ ] GREEN, 커밋 `feat(lookup): 블룸 필터 일괄 추가`

### Task 2: 지문 일괄 저장 (verdict)
**Files:** `verdict/domain/FileFingerprintStore.java`(+`saveImportedBatch`), `verdict/infrastructure/JdbcFileFingerprintStore.java`, 새 `verdict/application/ImportFileFingerprints.java`.
**Produces:** `int importBatch(Map<String,String> tlshBySha, String provenanceDetail, UUID actor)` — `@Transactional(MANDATORY)`, 활성 블랙리스트 SHA에만 저장, VERIFIED CALCULATED는 덮어쓰지 않음, 저장된 수를 돌려준다. 감사 로그는 없다(호출자가 남김).
- [ ] Task 3 통합 테스트로 확인한다(이 Task 단독 테스트는 두지 않는다).
- [ ] 커밋은 Task 3과 함께.

### Task 3: 묶음 가져오기 유스케이스와 API
**Files:** 새 `hashlist/application/ImportBlacklistBatchUseCase.java`, 새 `hashlist/domain/BlacklistBulkWriter.java` + `hashlist/infrastructure/JdbcBlacklistBulkWriter.java`, `hashlist/interfaces/BlacklistController.java`(+요청·응답 record), `audit/domain/AuditAction.java`(+`IMPORT_BLACKLIST`). Test: 새 `BlacklistImportRestIntegrationTest`.
- [ ] RED 테스트 (각 테스트마다 무작위 SHA):
  - `새_해시는_IMPORT로_들어가고_TLSH_지문도_들어간다`: 2건 → added=2, fingerprintsAdded=1(TLSH 있는 1건). DB: source=IMPORT, reason="MalwareBazaar: Mirai", 지문 provenance=ADMIN_IMPORT. 블룸 mightContain=true.
  - `이미_활성인_해시는_건너뛰지만_지문은_보강한다`: 활성 시드 + TLSH → skippedActive=1, fingerprintsAdded=1.
  - `해제된_해시는_되살리지_않는다`: is_active=false 시드 → skippedInactive=1, 여전히 비활성, 지문 없음.
  - `유효한_화이트리스트_해시는_건너뛴다` → skippedWhitelisted=1, 블랙리스트 행 없음.
  - `잘못된_SHA와_TLSH는_세고_건너뛴다`: sha "xyz" → invalid=1. tlsh "bad" → 블랙리스트는 들어가고 fingerprintsSkipped=1.
  - `VERIFIED_UPLOAD_지문은_덮어쓰지_않는다`: VERIFIED CALCULATED 지문 시드 → fingerprintsAdded=0, tlsh 그대로.
  - `천_건을_넘으면_400`: 1,001건 → 400.
  - `같은_묶음을_두_번_보내도_결과가_같다`: 두 번째는 added=0, skippedActive=n, fingerprintsAdded=0(이미 같은 ADMIN_IMPORT 값이면 0으로 셈).
  - `감사_로그는_묶음당_한_건`: action IMPORT_BLACKLIST 1건, detail에 source와 added.
  - `한_건_등록과_동시에_돌려도_교착이_없다`: 스레드 2개(묶음 200건 / 같은 SHA 일부를 한 건씩 등록) 10초 안에 끝나고, 둘 다 예외 없음(409는 정상).
- [ ] GREEN, `make test-api` 전체, 커밋 `feat(hashlist): 블랙리스트 일괄 가져오기 API (최대 1,000건 묶음)`

### Task 4: 적재 스크립트
**Files:** `infra/scripts/import_malwarebazaar.py`, `infra/scripts/test_import_malwarebazaar.py`, `docs/blacklist-import.md` (백업본 `_backup/2026-11-16/blacklist-import/`에서 가져와 고친다).
- [ ] RED (unittest):
  - CSV를 스트리밍으로 읽어 Entry(sha256, tlsh, label)를 낸다(`n/a` → None).
  - 1,000건씩 묶는다.
  - Auth-Key는 `--auth-key-file`, 없으면 `ABUSECH_AUTH_KEY`. 둘 다 없으면 다운로드 모드에서 종료 코드 2.
  - localhost가 아니면 `--allow-remote` 없이 거부한다.
  - 묶음 응답 건수를 합산한다.
- [ ] GREEN: `python3 -m unittest infra/scripts/test_import_malwarebazaar.py`. 커밋 `feat: MalwareBazaar 전체 덤프를 묶음 API로 넣는 스크립트`

### Task 5: 117만 건 리허설 (확인만)
- [ ] 별도 compose 프로젝트(`-p bulk-check`), `LOOKUP_BLOOMFILTER_EXPECTEDHASHCOUNT=1500000`.
- [ ] 미리 받은 `full.csv.zip`으로 `--csv`, 전체 적재. 소요 시간, 실패, 묶음당 지연, DB 크기(`pg_total_relation_size`)를 기록한다.
- [ ] 적재 뒤 CheckHash 지연을 잰다: TLSH 없는 요청 vs 81만 corpus TLSH 비교 요청, 각 10회.
- [ ] 정리(`down -v`), 결과를 PR과 측정 문서에 남긴다.
