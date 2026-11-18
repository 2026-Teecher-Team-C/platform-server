# 블룸 재구축: 목록 재투입 대신 원자적 OR 병합 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 블룸 재구축이 CPU를 포화시키지 않게 한다(운영 2026-11-17: 재구축 1회 344초, 주기 300초, SETBIT 초당 2.1만, api-server 92%·Redis 83% CPU 상시).

**Architecture:** `BloomFilterRebuildService.rebuild()`의 두 번째 단계(`addAll(findAllListHashes())`, 목록 117만 건 × k SETBIT)를 없앤다. 대신 `RedisBloomFilterPortImpl.rebuild()`가 새 비트맵을 임시 키에 SET한 뒤 Lua 스크립트 하나로 `BITOP OR 임시 임시 live` → `RENAME 임시 live`를 원자적으로 실행한다. 스냅샷 이후 `add`/`addAll`로 live에 들어간 비트가 교체 뒤에도 남는다.

**Tech Stack:** Spring Data Redis(Lettuce), Redis 8 Lua, JUnit 5 + Testcontainers.

## 설계 결정 (2026-11-17, 사용자 선택: "합쳐서 교체")

- 목적: 재구축 사이 "스냅샷 뒤 커밋된 목록 행"을 잃지 않는 것은 그대로 지킨다. 방법만 117만 건 재투입 → 비트맵 OR로 바꾼다.
- 대가: live에 있던 옛 비트는 재구축으로 지워지지 않는다. `hash_blacklist`/`hash_whitelist`/`file_verdicts`는 하드 삭제 경로가 없어(코드 확인) 지금은 지울 비트가 없다. 지우는 기능이 생기면 다시 본다.
- SETBIT은 켠 비트까지만 문자열을 늘려 live가 새 비트맵보다 짧을 수 있다(Redis 초기화 뒤) → 짧거나 같으면 OR한다. live가 더 길면 예전에 비트 수가 더 큰 설정이었으므로 OR 없이 교체한다. 더 작은 설정의 비트가 섞여도 오탐만 늘고 누락은 없다.
- 재구축 주기(5분)는 그대로 둔다. 개선 뒤 시간을 Grafana `tasks_scheduled_execution_seconds{code_function="rebuild"}`로 확인한다.

## Task 1: 원자적 OR 병합 재구축

**Files:**
- Modify: `api-server/src/main/java/com/teecherteamc/platform/lookup/infrastructure/RedisBloomFilterPortImpl.java` (`rebuild`)
- Modify: `api-server/src/main/java/com/teecherteamc/platform/lookup/application/BloomFilterRebuildService.java` (목록 재투입 제거)
- Modify: `api-server/src/test/java/com/teecherteamc/platform/CheckHashGrpcIntegrationTest.java` (`rebuild_reapplies_list_hashes_in_bulk_not_one_by_one` → 재투입하지 않음으로 바꿈)
- Test: `api-server/src/test/java/com/teecherteamc/platform/BloomFilterRebuildMergeIntegrationTest.java`

- [ ] **Step 1: 실패하는 테스트**
  - `재구축_중_즉시_추가된_해시는_교체_뒤에도_남는다`: `add(X)` → `rebuild(List.of(Y))` → `mightContain(X)`와 `mightContain(Y)` 모두 true
  - `비트_수가_다른_옛_블룸은_합치지_않고_교체한다`: live 키를 길이 3바이트 `0xFFFFFF`로 덮어씀 → `rebuild(List.of(Y))` → `STRLEN` = `(bitSize+7)/8`
  - `CheckHashGrpcIntegrationTest`: 재구축이 `addAll`을 호출하지 않는다(`verify(bloomFilterPort, never()).addAll(any())`)
- [ ] **Step 2: 실패 확인** — 첫째와 셋째가 FAIL(둘째는 회귀 방지용이라 지금도 통과할 수 있다)
- [ ] **Step 3: 구현** — Lua:
  ```lua
  if redis.call('STRLEN', KEYS[2]) <= tonumber(ARGV[1]) then
    redis.call('BITOP', 'OR', KEYS[1], KEYS[1], KEYS[2])
  end
  return redis.call('RENAME', KEYS[1], KEYS[2])
  ```
  KEYS[1]=임시 키, KEYS[2]=live 키, ARGV[1]=비트맵 바이트 수. 서비스는 `rebuild(findAllCachedHashes())`만 호출한다.
- [ ] **Step 4: 통과 확인** — 새 테스트와 `CheckHashGrpcIntegrationTest` PASS, 이어서 api-server 전체 PASS
- [ ] **Step 5: 커밋** — `perf(lookup): 블룸 재구축이 목록 117만 건을 다시 넣지 않고 live와 원자적으로 합친다`
