# 대시보드 규모 측정

대기업 규모 목데이터(PC 1,000 / 3,000 / 10,000대, 180일, PC당 하루 20건)에서 위협 대시보드 API 응답 시간을 잰다.
운영 사양(2 vCPU, postgres 768m, api-server 1536m)으로 묶은 로컬 Docker에서 돈다. 읽기만 잰다(쓰기 부하는 측정 밖).

## 구성

| 파일 | 역할 |
|---|---|
| `infra/compose.dashboard-scale.yml` | `compose.yml + compose.temp-db.yml + compose.loadtest.yml` 위 덮어쓰기. DB 이름 `platform_dashscale`, postgres auto_explain(1초 초과 쿼리 계획 로그), 측정 컨테이너 `dashscale-client` |
| `infra/dashboard-scale/run.sh` | up / blacklist / bench / down |
| `seed.sql` | 목데이터 생성(한 트랜잭션, `TRUNCATE ... CASCADE` 포함). `confirm_wipe=yes`, `expect_db=platform_dashscale` 없으면 실패 |
| `bench.py` | 기준선(엔드포인트 하나씩, 기간 1·7·30·90일), 폴링(관리자 1·5명이 5초 간격으로 화면 전체 요청) |

프로젝트 이름은 `platform-dashscale`이라 `make dev`·`make loadtest`의 컨테이너·볼륨과 섞이지 않는다. 호스트 포트는 열지 않는다.
DB 이름을 `platform_dashscale`로 따로 둔 이유: dev·loadtest DB는 모두 `platform`이라 seed.sql의 `expect_db` 안전장치로 구별할 수 없다.

## 순서

```
make dashboard-scale-up                       # 스택 기동(빌드 포함, 몇 분)
make dashboard-scale-seed                     # 117만 건 블랙리스트 시드. 첫 실행 때 한 번(약 20분). FILE=<경로>로 위치 지정
make dashboard-scale-bench PCS=1000           # 한 단계 (3000, 10000도 같은 방식)
make dashboard-scale-down                     # 컨테이너 정리(볼륨은 남긴다)
```

시드 파일(`malicious-fingerprints-*.csv.gz`)은 `loadtest-seed/` 또는 `../loadtest-seed/`에서 찾는다(`infra/loadtest/README.md` 참고).
BLACKLIST 판정 이벤트가 활성 블랙리스트 해시를 쓰므로 블랙리스트가 비어 있으면 bench가 멈춘다.

### 빠른 확인(스모크)

```
make dashboard-scale-up
make dashboard-scale-bench PCS=50 QUICK=1 FIXTURE_BLACKLIST=1
```

- `QUICK=1`: 기간 7일 하나, n=3, 폴링 2라운드.
- `FIXTURE_BLACKLIST=1`: 블랙리스트가 비어 있을 때 가짜 해시 50건(reason=`dashscale-fixture`)을 넣는다. 확인용이다. 실측(이 변수 없이 bench) 시작 때 이 행들을 자동으로 지운다(볼륨은 그대로). 실제 측정(117만 건)은 `dashboard-scale-seed`를 쓴다.

## 단계 하나가 하는 일

생성(seed.sql, 기준 시각 = 시작 시각) → `VACUUM (ANALYZE)`(시드한 표) + autovacuum 워커가 없어질 때까지 대기(상한 `AUTOVACUUM_WAIT_S`=1200초, 경과 시간 출력) → 기준선 → 폴링 1명 → 폴링 5명. 그동안 `docker stats`를 5초마다 기록하고, 끝나면 auto_explain 로그를 저장한다.
같은 단계를 다시 돌리면 대상 테이블을 비우고 다시 만든다.

## 결과: `infra/dashboard-scale/out/pcs-<N>/` (커밋하지 않는다)

`baseline.csv`, `polling-1.csv`, `polling-5.csv`, `summary.csv`(p50/p95/max/errors), `stats.csv`(컨테이너 CPU·메모리), `auto_explain.log`.
판정 기준: 30일 기간 p95 ≤ 1000ms, 폴링 화면 전체(가장 늦은 응답) p95 ≤ 5000ms.

## 환경 변수

`PCS`(필수), `DAYS`(180), `PER_DAY`(20), `AUTOVACUUM_WAIT_S`(1200), `QUICK`, `FIXTURE_BLACKLIST`, `FILE`, `SEED_SHA256`, `SEED`(난수 고정값, 기본 0.42).
`LOADTEST_SERVER_CPUSET`/`LOADTEST_CLIENT_CPUSET`는 loadtest와 같다(기본: 서버 CPU 0,1, 나머지는 클라이언트).

## 주의

- `seed.sql`은 측정 전용 DB(`platform_dashscale`)에서만 돈다. 운영·공유 DB 금지.
- 볼륨(`platform-dashscale_postgres-data`)은 `down`으로 지워지지 않는다. 지우려면 사용자가 직접 `docker volume rm`.

## 해석할 때 알아둘 것

- **폴링 p95**: 기본 24라운드라 p95는 사실상 최대값이다(24개 중 23번째 이상). 요약의 `n`(라운드 수)을 같이 읽는다.
- **기준선 타임아웃**: 기준선은 느린 쿼리의 실제 시간을 보려고 요청당 상한을 120초(`BASELINE_TIMEOUT_S`)로 둔다. 폴링은 콘솔과 같은 8초다.
  기준선 요청이 120초를 넘기면 오류로 세고 `ms=120000`으로 기록한다(요약의 `errors`와 `max_ms`에 나온다).
- **auto_explain**: `log_timing=off`라 로그의 실행 계획에는 노드별 시간이 없다(행 수·버퍼만). 계획 전체의 `duration`은 있다.
  노드별 시간이 필요하면 해당 쿼리를 `EXPLAIN (ANALYZE)`로 따로 돌린다.
- **데이터 가정**(운영 실측 전의 기본값):
  - 판정 비율: RELEASED 98.6%(CACHE 70 / ENGINE 25 / WHITELIST 5), BLOCKED 1.0%(BLACKLIST 40 / SIMILARITY 20 / ENGINE 25 / CACHE 15), FAIL_CLOSE 0.2%, BYPASSED 0.2%(POLICY).
  - 정상 sha 풀 = PC 수 x 50(악성 PC x 5, 변종 PC x 1, 호스트 PC / 2). 이벤트는 거듭제곱 분포로 풀에서 고른다.
  - 시각: 평일 1.0 / 주말 0.2, 80%는 09~18시 KST. 마지막 24시간은 평일 하루치에 시간대 가중치를 곱한 양이다.
  - 에이전트의 약 3%는 REVOKED.
  - 운영 비율을 조회하면 `seed.sql`의 비율 표를 바꾼다.
- **메모리**: seed.sql은 이벤트를 날짜마다 INSERT 한 문장으로 넣는다. 한 문장이면 FK 트리거 큐가 10k PC에서 약 860MB가 돼 postgres(768m)가 OOM이 된다.
