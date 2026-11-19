# TLSH 비교: 메모리 상주 압축 corpus Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** CheckHash TLSH 비교가 요청마다 DB에서 후보 10만 건 안팎을 읽고 문자열을 파싱하지 않게 한다. 운영 부하 시험(2026-11-18, 락 제거 후)에서 처리량 0.75건/s, 동시 8에서 94% 타임아웃이었다.

**Architecture:** api-server가 활성 악성 지문 전체(운영 117만 건)를 지문 35바이트 + SHA 32바이트의 평평한 `byte[]`로 메모리에 둔다(약 80MB). L-value별로 묶어 두고 입력의 L ±5 묶음만 바이트 조회표로 비교한다. 스냅샷은 `current_similarity_corpus_token`에 묶이며, 요청 트랜잭션의 토큰과 같을 때만 쓴다. 다르면(기동 직후·목록 변경 직후) 지금의 DB 경로(indexed)로 답하고 백그라운드에서 다시 만든다.

**Tech Stack:** Java 21, Spring JDBC, JUnit 5 + Testcontainers, 기존 `com.trendmicro.tlsh`.

## Global Constraints

- 정확성: 결과(기준 샘플·거리·동률 규칙 "SHA가 작은 쪽")가 `Tlsh.totalDiff(…, true)`와 L ±5 창을 쓰는 indexed 경로와 같아야 한다. 오래된 스냅샷으로 답하지 않는다(토큰이 다르면 DB 경로).
- 형식: SHA는 `^[0-9a-f]{64}$`, 지문은 `^T1[0-9A-F]{70}$`(V8 CHECK). 그래서 바이트 표현이 왕복되고, 바이트 사전순 = 문자열 순서다. 형식 밖 입력은 DB 경로로 보낸다.
- 재구축은 REPEATABLE READ 읽기 전용 트랜잭션 하나에서 토큰과 행을 함께 읽는다(같은 MVCC 스냅샷). advisory 공유 락은 잡지 않는다(목록 쓰기를 수십 초 막지 않게).
- 재구축은 한 번에 하나, 최소 간격 30초(대량 등록 중 토큰이 계속 바뀌어도 재구축이 CPU를 잡아먹지 않게).
- 되돌리기: `SIMILARITY_MODE=indexed`면 메모리 경로를 끈다(기본은 `memory`).

## Task 1: `TlshBytes` — 바이트 표현과 거리

- 실패하는 테스트 `TlshBytesTest`: 실제 지문(TlshCreator, 길이 다양) 쌍 2,000개 + 무작위 hex 지문 쌍 2,000개에서 `distance == Tlsh.totalDiff(…, true)`, `encode(decode(t)) == t`, `lvalue(decode(t)) == TlshLValue.of(t)`.
- 구현: `decode(String) -> byte[35]`, `distance(byte[] a, int aOff, byte[] b, int bOff)`, `lvalue(byte[], off)`, `encode(byte[], off)`. 헤더는 체크섬 1바이트(같음/다름), L(니블 교환), Q1/Q2(상·하위 니블), 본문 32바이트는 256×256 표(라이브러리 `bit_pairs_diff_table`과 같은 규칙).

## Task 2: `TlshCorpusSnapshot` — L 묶음과 최근접 탐색

- 실패하는 테스트: 무작위 corpus 3,000개(변종 포함)와 입력 500개에서 `nearest(input)`가 라이브러리로 L ±5 창 안을 전부 비교한 결과(샘플·거리, 동률은 작은 SHA)와 같다. 빈 창이면 결과 없음.
- 구현: 빌더가 (sha, tlsh)를 받아 L 기준 계수 정렬, `int[257]` 시작 위치. `nearest`는 `TlshLValue.window` 11개 묶음만 훑는다.

## Task 3: `InMemoryTlshCorpus` + 저장소 연결

- 실패하는 테스트:
  - 단위: 토큰이 같으면 스냅샷 결과, 다르면 빈 결과 + 재구축 1회 요청(동시 호출에도 1회), 30초 안 재요청 무시.
  - 통합 `TlshCompareModeEquivalenceIntegrationTest`: 같은 트랜잭션에서 만든 스냅샷을 넣은 memory 저장소가 bruteforce와 같은 결론(시나리오 C 정의).
  - 통합: memory 모드에서 스냅샷 토큰이 현재와 다르면 DB 경로로 답한다(새로 넣은 블랙리스트 지문을 바로 찾는다).
- 구현: `JdbcSimilarityDecisionStore.compareTlsh`가 memory 모드·T1 형식이면 `token()`으로 스냅샷을 조회, 없으면 기존 경로. 기동 후(ApplicationReadyEvent) 백그라운드 첫 구축. 지표: `verdict.similarity.memory{result=hit|stale}`, `verdict.similarity.memory.size`.
- 전체 테스트 PASS, 커밋.

## 배포 뒤

운영에서 구축 시간·힙 사용량 확인, 같은 입력으로 부하 시험(동시 1→16) 재측정, Grafana 스크린샷.
