# sha256 CHAR(64) → VARCHAR(64) 전환 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** public 스키마의 `character(64)` 컬럼 10개를 `VARCHAR(64)`로 바꿔 sha256 ↔ hash_value 비교가 캐스트 없이 인덱스를 쓰게 한다.

**Architecture:** Flyway `V18` 한 파일이 의존 뷰 3개를 지우고, 컬럼 타입을 바꾸고, 뷰 3개와 `revise_similarity_corpus()`를 캐스트 없이 다시 만든다. JPA 엔티티 4개의 `@JdbcTypeCode(SqlTypes.CHAR)`를 지워 `ddl-auto: validate`를 통과시킨다.

**Tech Stack:** PostgreSQL 17, Flyway, Spring Boot 4 / Hibernate 7, JUnit 5 + Testcontainers.

**Spec:** `docs/superpowers/specs/2026-11-17-char-to-varchar-design.md`

## Global Constraints

- 기존 마이그레이션 파일은 수정하지 않는다. 새 파일은 `V18__sha256_varchar.sql`.
- 길이·NOT NULL·PK·FK·CHECK·인덱스는 바꾸지 않는다. 타입만 `VARCHAR(64)`.
- 뷰·함수는 "마지막으로 만든 마이그레이션"의 정의를 그대로 옮긴다: `download_event_threats` = V17(단, `::text` 제거), `active_malicious_fingerprints` = V8, `current_similarity_corpus_token` = V10, `revise_similarity_corpus()` = V15(단, `::bpchar` 제거).
- 테스트 실행은 Docker 안 Gradle: `docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -v "$PWD":/workspace -v platform-gradle-cache:/root/.gradle -w /workspace/api-server eclipse-temurin:21-jdk ./gradlew --no-daemon test [--tests ...]` (아래 `GRADLE`).
- 브랜치 `refactor/sha256-varchar`는 PR #103(`fix/threat-view-blacklist-index`) 위에 있다. PR은 #103 머지 뒤 develop 대상으로 연다.

## Review Focus

1. FK로 묶인 CHAR 컬럼(`similarity_assessments.sha256` → `file_verdicts.sha256` 등): 한쪽만 먼저 바뀌는 중간 상태에서 FK 재검증이 실패하면 안 된다 → 참조되는 쪽과 참조하는 쪽을 한 마이그레이션 안에서 모두 바꾸고, Task 1의 마이그레이션 실행 테스트로 확인한다.
2. `revise_similarity_corpus()`에 `::bpchar`가 남으면 이제 varchar 컬럼 쪽이 bpchar로 바뀌어 V15가 고친 117만 행 순차 스캔이 되살아난다 → Task 1 테스트 `유사도_트리거_함수에_bpchar_캐스트가_없다`.
3. 다시 만든 뷰가 이전과 다른 결과를 내면 안 된다 → 기존 `DashboardThreatViewIntegrationTest`·유사도 테스트 전체 통과로 확인한다.
4. 기존 데이터가 있는 DB에서의 업그레이드 → `DevelopMigrationUpgradeTest`로 확인한다(기대 개수 갱신).
5. 운영 잠금 시간 → Task 2에서 117만 행으로 측정한다.

---

### Task 1: V18 마이그레이션과 엔티티 매핑

**Files:**
- Create: `api-server/src/main/resources/db/migration/V18__sha256_varchar.sql`
- Modify: `api-server/src/main/java/com/teecherteamc/platform/event/domain/DownloadEvent.java:35`, `.../quarantine/domain/QuarantineFile.java:35`, `.../verdict/domain/Analysis.java:35`, `.../verdict/domain/FileVerdict.java:26` — `@JdbcTypeCode(SqlTypes.CHAR)`와 쓰지 않게 된 import 제거
- Modify: `api-server/src/test/java/com/teecherteamc/platform/DevelopMigrationUpgradeTest.java:45,49` — `isEqualTo(10)`→`11`, `hasSize(17)`→`18`
- Test: `api-server/src/test/java/com/teecherteamc/platform/Sha256VarcharSchemaIntegrationTest.java`

**Interfaces:**
- Consumes: V17까지의 스키마, PR #103의 `HashBlacklistIndexUseIntegrationTest`(그대로 통과해야 함).
- Produces: 없음(스키마만).

- [ ] **Step 1: 실패하는 테스트 작성** — `@Import(TestcontainersConfiguration.class) @SpringBootTest`, `JdbcTemplate` 사용.

```java
@Test
void public_스키마에_character_타입_컬럼이_없다() {
    List<String> columns = jdbcTemplate.queryForList(
            "SELECT table_name || '.' || column_name FROM information_schema.columns "
                    + "WHERE table_schema = 'public' AND data_type = 'character'", String.class);
    assertThat(columns).isEmpty();
}

@Test
void 유사도_트리거_함수에_bpchar_캐스트가_없다() {
    String source = jdbcTemplate.queryForObject(
            "SELECT prosrc FROM pg_proc WHERE proname = 'revise_similarity_corpus'", String.class);
    assertThat(source).doesNotContain("bpchar");
}
```

- [ ] **Step 2: 실패 확인** — `GRADLE test --tests '*Sha256VarcharSchemaIntegrationTest'` → 두 테스트 FAIL(첫째는 10개 컬럼 목록, 둘째는 `bpchar` 포함).

- [ ] **Step 3: `V18__sha256_varchar.sql` 작성** — 순서: ① `DROP VIEW download_event_threats, current_similarity_corpus_token, active_malicious_fingerprints;` ② 아래 10개 `ALTER TABLE ... ALTER COLUMN ... TYPE VARCHAR(64);` — `file_fingerprints.sha256`, `download_events.sha256`, `analyses.sha256`, `file_verdicts.sha256`, `event_decision_evidence.sha256`, `similarity_assessments.sha256`, `similarity_assessments.matched_sample_sha256`, `similarity_cache.sha256`, `pending_quarantine_uploads.sha256`, `quarantine_files.sha256` ③ 뷰 3개 재생성(Global Constraints의 출처대로, 의존 순서 지킴) ④ `CREATE OR REPLACE FUNCTION revise_similarity_corpus()`를 V15 본문에서 `::bpchar` 두 곳만 뺀 형태로. 머리 주석에 왜(bpchar 캐스트로 인덱스 미사용, V15·V17 참조)와 운영 잠금을 적는다. FK 때문에 ALTER가 실패하면 해당 FK를 `DROP CONSTRAINT` 후 같은 이름·정의로 다시 `ADD`한다.

- [ ] **Step 4: 엔티티 4개에서 `@JdbcTypeCode(SqlTypes.CHAR)` 제거, `DevelopMigrationUpgradeTest` 기대값 갱신(11, 18).**

- [ ] **Step 5: 통과 확인** — `GRADLE test --tests '*Sha256VarcharSchemaIntegrationTest' --tests '*HashBlacklistIndexUseIntegrationTest' --tests '*DevelopMigrationUpgradeTest'` → 전부 PASS. 이어서 `GRADLE test` 전체 → 실패 0.

- [ ] **Step 6: 커밋**

```bash
git add api-server/src/main/resources/db/migration/V18__sha256_varchar.sql api-server/src/main/java api-server/src/test/java docs/superpowers
git commit -m "refactor(schema): sha256 컬럼을 CHAR(64)에서 VARCHAR(64)로 바꿔 캐스트 없이 인덱스를 쓰게 한다"
```

### Task 2: 운영 잠금 시간 측정 (코드 변경 없음)

- [ ] **Step 1:** 일회용 Postgres 17 컨테이너에 V1~V17을 적용하고 `file_fingerprints`에 117만 행(`generate_series`, 유효한 64자 hex, `calculation_status='CALCULATED'`)을 넣는다. 트리거 잠금 한도 때문에 `ALTER TABLE file_fingerprints DISABLE TRIGGER USER`로 적재 후 다시 켠다.
- [ ] **Step 2:** `\timing on`으로 V18 본문을 실행해 걸린 시간을 기록한다. 기대: 수십 초 이하. 결과를 PR 본문 "배포 시 영향"에 적는다.
- [ ] **Step 3:** 컨테이너를 지운다(`docker rm -f`).
