# CheckHash 측정 지표와 exporter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** CheckHash 단계별·전체 지연, TLSH corpus 크기·캐시 적중을 Prometheus로 내보내고, 호스트·Postgres·Redis·컨테이너 exporter를 붙여 맥북 Grafana에서 고도화 테스트를 볼 수 있게 한다.

**Architecture:** PR 두 개. ① api-server(브랜치 `feat/checkhash-metrics`): Micrometer `MeterRegistry`로 `CheckHashUseCase` 단계를 감싸고, `ParsedSimilarityCorpus`에 카운터·게이지, `JdbcSimilarityDecisionStore`에 `similarity.corpus.limit`. ② infra(브랜치 `chore/exporters`): compose에 exporter 4종, `prometheus.yml`에 scrape job.

**Tech Stack:** Spring Boot 4 / Micrometer(Prometheus registry 이미 있음), JUnit 5 + Testcontainers, Docker Compose, Prometheus v3.6.

**Spec:** `project/docs/advancement/2026-11-17-advancement-test-setup-report.md` 1-1, 1-2, 1-5 (설계 레포). 패널 정의는 같은 폴더 `고도화 테스트.md`.

## Global Constraints

- 지표 이름(Prometheus 표기): `verdict_checkhash_stage_seconds{stage}`, `verdict_checkhash_seconds{decision,source}`, `verdict_similarity_corpus_size`, `verdict_similarity_cache_hits_total`, `verdict_similarity_cache_misses_total`. Micrometer 이름은 점 표기(`verdict.checkhash.stage` 등).
- `stage` 값: `claim`, `corpus_lock`, `bloom`, `lists`, `cache`, `tlsh_compare`, `tlsh_save`.
- 히스토그램: 두 CheckHash 타이머에 percentiles-histogram, SLO 경계 `100ms`, `1s`.
- `similarity.corpus.limit`: 기본 무제한(0 또는 미설정). 양수면 corpus 쿼리에 `LIMIT`.
- 로그 MDC에 `event_id`(CheckHash·SubmitFile).
- `domain` 패키지는 Micrometer에 의존하지 않는다(CLAUDE.md). 지표 코드는 `application`/`infrastructure`.
- exporter: node_exporter, postgres_exporter(`pg_stat_statements` 사용), redis_exporter, cAdvisor(수집 간격 30초). 호스트 포트는 열지 않는다(Prometheus만 `127.0.0.1:9090`, #106).
- EC2 available 메모리가 500MB 미만이면 cAdvisor를 뺀다(스펙 1-5).

## Review Focus

1. 조기 반환 경로(화이트리스트·블랙리스트·캐시·corpus try-lock 실패·TLSH 없음)도 전체 타이머가 decision/source 태그로 한 번씩 기록돼야 한다 → Task 1 테스트가 `UNKNOWN`(TLSH 없음)과 `BLOCK/BLACKLIST` 두 경로를 확인.
2. 예외로 끝난 CheckHash도 전체 타이머가 남아야 한다(`decision=ERROR`) → Task 1 구현에서 try/finally.
3. corpus가 캐시 상한(8,192)을 넘으면 매번 miss로 세야 한다(지금 운영 상황) → Task 2 테스트.
4. `similarity.corpus.limit`이 캐시 토큰과 섞여 다른 상한의 스냅샷을 재사용하면 안 된다 → 상한은 재시작으로만 바뀌므로 토큰에 넣지 않되, 상한이 걸린 결과는 캐시에 넣지 않는다(Task 2 테스트).
5. postgres_exporter가 DB 연결을 못 해도 다른 서비스 기동을 막으면 안 된다 → depends_on 없이 붙이고 Task 3에서 기동 확인.

---

### Task 1: CheckHash 단계·전체 타이머, 히스토그램, MDC

**Files:**
- Create: `api-server/src/main/java/com/teecherteamc/platform/lookup/application/CheckHashMetrics.java`
- Modify: `api-server/src/main/java/com/teecherteamc/platform/lookup/application/CheckHashUseCase.java`
- Modify: `api-server/src/main/java/com/teecherteamc/platform/verdict/interfaces/VerdictGrpcService.java` (CheckHash·SubmitFile 처리 동안 `MDC.put("event_id", …)`, finally에서 remove)
- Modify: `api-server/src/main/resources/application.yml` (`management.metrics.distribution.percentiles-histogram` / `slo` for `verdict.checkhash`, `verdict.checkhash.stage`)
- Test: `api-server/src/test/java/com/teecherteamc/platform/CheckHashMetricsIntegrationTest.java`

**Interfaces:**
- Produces: `CheckHashMetrics` (`@Component`): `<T> T stage(String stage, Supplier<T> work)`, `void stage(String stage, Runnable work)`, `void record(long startNanos, String decision, String source)`.

- [ ] **Step 1: 실패하는 테스트** — `CheckHashGrpcIntegrationTest`의 호출 방식을 따라 gRPC로 CheckHash 2회(블랙리스트 해시 → BLOCK/BLACKLIST, 모르는 해시 + TLSH 없음 → UNKNOWN). 단언:
  - `registry.find("verdict.checkhash").tags("decision","BLOCK","source","BLACKLIST").timer().count() >= 1`
  - `registry.find("verdict.checkhash").tags("decision","UNKNOWN","source","NONE").timer().count() >= 1`
  - `registry.find("verdict.checkhash.stage").tag("stage","bloom").timer()` 존재, `stage=lists` 존재
  - `/actuator/prometheus` 본문에 `verdict_checkhash_seconds_bucket`와 `le="0.1"`, `le="1.0"` 포함
- [ ] **Step 2: 실패 확인** — `gt.sh <worktree> test --tests '*CheckHashMetricsIntegrationTest'` → FAIL(지표 없음)
- [ ] **Step 3: 구현** — `CheckHashUseCase.checkHash`를 try/finally로 감싸 전체 시간을 `record`(source 없으면 `NONE`, 예외면 `decision=ERROR`). 각 단계 호출을 `metrics.stage(...)`로 감싼다: claim=`evidence.claim`, corpus_lock=`tryLockCorpus`, bloom=`bloomMightContain`, lists=화이트·블랙리스트 조회와 `exactOverride`, cache=`fromCache`, tlsh_compare=`compareClaimed`, tlsh_save=`saveClaimed`.
- [ ] **Step 4: 통과 확인** — 같은 명령 PASS, 이어서 `CheckHashGrpcIntegrationTest`·`TlshSimilarityGrpcIntegrationTest` PASS
- [ ] **Step 5: 커밋** — `feat(metrics): CheckHash 단계별·전체 지연 타이머와 event_id 로그 MDC`

### Task 2: corpus 크기·캐시 적중 지표와 `similarity.corpus.limit`

**Files:**
- Modify: `api-server/src/main/java/com/teecherteamc/platform/verdict/infrastructure/ParsedSimilarityCorpus.java` (생성자에 `MeterRegistry`; hit/miss 카운터, 마지막 방문 후보 수 게이지)
- Modify: `api-server/src/main/java/com/teecherteamc/platform/verdict/infrastructure/JdbcSimilarityDecisionStore.java` (`@Value("${similarity.corpus.limit:0}") int limit`; 양수면 `... LIMIT ?`)
- Modify: `api-server/src/main/resources/application.yml` (`similarity.corpus.limit: ${SIMILARITY_CORPUS_LIMIT:0}`)
- Test: `api-server/src/test/java/com/teecherteamc/platform/verdict/infrastructure/ParsedSimilarityCorpusMetricsTest.java` (단위, `SimpleMeterRegistry`)
- Test: `api-server/src/test/java/com/teecherteamc/platform/SimilarityCorpusLimitIntegrationTest.java`

**Interfaces:**
- Consumes: 없음(Task 1과 독립)
- Produces: `ParsedSimilarityCorpus(MeterRegistry registry)`; 지표 `verdict.similarity.cache.hits`, `verdict.similarity.cache.misses`(카운터), `verdict.similarity.corpus.size`(게이지).

- [ ] **Step 1: 실패하는 테스트** — 단위: 후보 3개 로더로 같은 토큰 `visit` 2회 → misses=1, hits=1, size=3. 후보 8,193개 → 2회 모두 miss, size=8193. 통합: `@TestPropertySource(properties="similarity.corpus.limit=2")`, 활성 악성 지문 5개 시드 후 `compareTlsh` → size 게이지 2.
- [ ] **Step 2: 실패 확인** — 두 테스트 FAIL(생성자·지표 없음)
- [ ] **Step 3: 구현** — 캐시 hit면 hits++ 후 size=스냅샷 크기, miss면 misses++ 후 방문한 후보 수를 size로. 상한이 걸린 결과도 같은 규칙(재시작으로만 바뀜).
- [ ] **Step 4: 통과 확인** — 두 테스트 PASS, `SimilarityCorpusReviewIntegrationTest` PASS, 이어서 api-server 전체 `test` PASS
- [ ] **Step 5: 커밋** — `feat(metrics): TLSH corpus 크기·캐시 적중 지표와 비교 대상 상한 설정`

### Task 3: exporter 4종과 scrape 설정 (별도 브랜치·PR)

**Files:**
- Modify: `infra/compose.yml` (서비스 `node-exporter`, `postgres-exporter`, `redis-exporter`, `cadvisor`; 포트 publish 없음)
- Modify: `infra/prometheus/prometheus.yml` (job 4개; cadvisor `scrape_interval: 30s`)
- Modify: `infra/dev.env` (postgres_exporter 접속 값이 env에서 나오면 기본값)

**Interfaces:**
- Consumes: Postgres `pg_stat_statements` 로드(#100 반영 완료), Prometheus `127.0.0.1:9090`(#106)

- [ ] **Step 1:** 이미지 버전 고정: `prom/node-exporter:v1.9.1`, `prometheuscommunity/postgres-exporter:v0.17.1`, `oliver006/redis_exporter:v1.74.0`, `gcr.io/cadvisor/cadvisor:v0.52.1` (풀 가능 여부 확인).
- [ ] **Step 2:** compose와 scrape 설정 작성. node_exporter는 `/proc`, `/sys`, `/`를 `:ro`로 마운트하고 `--path.rootfs=/host`. postgres_exporter는 `DATA_SOURCE_URI=postgres:5432/platform?sslmode=disable`, 계정은 api-server와 같은 env 값(ruling 참고), `--collector.stat_statements`.
- [ ] **Step 3: 검증** — `docker compose ... config -q`(개발·운영 조합 둘 다), 로컬에서 exporter 4개를 띄워 `curl`로 `node_load1`, `pg_up 1`, `redis_up 1`, `container_memory_working_set_bytes`가 나오는지 확인, Prometheus targets 모두 `up`.
- [ ] **Step 4: 커밋** — `chore(infra): node·postgres·redis exporter와 cAdvisor를 Prometheus에 붙인다`

## Rulings (계획 단계)

- `similarity.mode`(bruteforce/indexed)는 이번 PR에서 뺀다 — 고를 대상(indexed)이 없는 설정은 쓸 데가 없다. TLSH 고도화 PR에서 구현과 함께 넣는다.
- postgres_exporter는 우선 api-server와 같은 DB 계정을 쓴다 — 기존 운영 DB에 읽기 전용 역할을 만들려면 Flyway 밖 수동 작업이나 비밀값 placeholder가 필요하다. 후속으로 `pg_monitor` 전용 계정을 만든다.
- Prometheus 포트는 스펙의 `9091`이 아니라 이미 배포한 `9090`(#106)을 쓴다.
