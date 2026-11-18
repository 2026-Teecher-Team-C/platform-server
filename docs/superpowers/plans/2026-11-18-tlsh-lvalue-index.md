# TLSH 비교: L-value 인덱스로 후보 줄이기 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** CheckHash TLSH 비교가 117만 행 전체 대신 L-value ±5 후보만 읽게 해 `tlsh_compare` p95를 10초에서 1~2초대로 낮춘다(설계 레포 고도화 테스트 5단계, `mode=indexed`).

**Architecture:** Flyway V19가 IMMUTABLE 함수 `tlsh_lvalue(text)`와 `file_fingerprints`의 부분 표현식 인덱스를 만든다. `JdbcSimilarityDecisionStore.compareTlsh`는 `similarity.mode=indexed`(기본)일 때 입력 L-value의 원형 ±5 창으로 `WHERE tlsh_lvalue(tlsh) = ANY(?)`를 붙이고, 캐시 키에 창을 넣는다. `bruteforce`는 동일성 검사(시나리오 C)와 되돌리기용으로 남긴다.

**Spec:** 설계 레포 `docs/advancement/2026-11-17-advancement-test-setup-report.md` 1-1(`similarity.mode`), 2-2(시나리오 C), 2-3(5~7단계). 근거: `third-party/tlsh/.../Tlsh.java` `totalDiff` — `ldiff = mod_diff(L1, L2, 256)`, 0→0, 1→1, 그 밖 `ldiff*12`.

## Global Constraints

- 정확성: 거리 ≤70(WARN 상한)인 후보는 하나도 빠지면 안 된다. ldiff ≥6이면 길이 페널티만 72라 제외해도 안전하다. 창은 원형(mod 256)이다.
- 동일성(시나리오 C)의 정의: 두 방식의 분류(BLOCK/WARN/NONE)가 같고, 분류가 BLOCK/WARN이면 기준 샘플·거리도 같다. NONE이면 가장 가까운 후보가 달라도 된다(창 밖 후보는 어차피 >70).
- 지문 형식은 "T1" + 대문자 hex 70자(V8 CHECK, `CheckHashQuery.claimedTlsh`). L-value는 0-based 문자 4~5의 hex를 니블 교환한 값(`TlshUtil.from_hex_swapped`).
- 기존 마이그레이션은 고치지 않는다. 인덱스 생성은 Flyway 트랜잭션 안의 일반 `CREATE INDEX`(쓰기만 잠깐 막고 읽기는 막지 않는다).

## Task 1: V19 — `tlsh_lvalue` 함수와 부분 인덱스

**Files:** Create `api-server/src/main/resources/db/migration/V19__tlsh_lvalue_index.sql`; Modify `DevelopMigrationUpgradeTest`(11→12, 18→19); Test `api-server/src/test/java/com/teecherteamc/platform/TlshLValueIndexIntegrationTest.java`

- [ ] 실패하는 테스트: (a) `SELECT tlsh_lvalue(?)`가 Java `TlshLValue.of(tlsh)`와 같다(골든 TLSH 여러 개, 니블 교환 확인용으로 L이 0x0F·0xF0 경계인 값 포함). (b) `SET LOCAL enable_seqscan=off` 후 `EXPLAIN SELECT sha256, tlsh FROM active_malicious_fingerprints WHERE tlsh_lvalue(tlsh) = ANY(?)`에 `idx_fingerprints_tlsh_lvalue`가 나온다.
- [ ] 구현: `CREATE FUNCTION tlsh_lvalue(t text) RETURNS smallint LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE`; `CREATE INDEX idx_fingerprints_tlsh_lvalue ON file_fingerprints (tlsh_lvalue(tlsh)) WHERE calculation_status = 'CALCULATED'`.

## Task 2: `TlshLValue`와 indexed 비교

**Files:** Create `api-server/src/main/java/com/teecherteamc/platform/verdict/infrastructure/TlshLValue.java` (`static int of(String tlsh)`, `static Integer[] window(int lvalue)` — 원형 ±5, 11개); Modify `JdbcSimilarityDecisionStore` (`@Value("${similarity.mode:indexed}")`, 값은 `indexed`/`bruteforce`, 그 밖이면 기동 실패); Modify `application.yml` (`similarity.mode: ${SIMILARITY_MODE:indexed}`)

- [ ] 실패하는 테스트:
  - `TlshLValueTest`(단위): 임의 바이트로 만든 TLSH 쌍 2,000개에서 원형 ldiff>5면 `totalDiff(…, true) >= 72`; `window(0)`에 251~255와 0~5가 들어간다.
  - `TlshCompareModeEquivalenceIntegrationTest`(시나리오 C): 임의 지문 1,000개 + 일부의 변종(거리 1~60)을 활성 악성으로 시드. 입력 300개(변종·무작위 섞음)에 대해 bruteforce와 indexed 결과가 위 동일성 정의를 만족한다(불일치 0건). 두 모드 스토어는 같은 DataSource로 직접 생성한다.
- [ ] 구현: indexed면 SQL에 `WHERE tlsh_lvalue(tlsh) = ANY(?)`와 창 배열을 바인딩, 캐시 키는 `token + "|L" + lvalue`. bruteforce는 지금과 같다.
- [ ] 전체 테스트 PASS, 커밋.

## 배포 뒤
시나리오 A를 같은 입력으로 다시 돌려 블룸 수정 후 수치와 비교하고, Grafana 스크린샷을 `project/screenshot/`에 저장한다.
