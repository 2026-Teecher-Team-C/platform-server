# 로컬 부하 시험: 업로드 + YARA 판정

운영 EC2(2 vCPU / 3.8GB)와 같은 크기로 묶은 서버에, 업로드 속도를 제한한 가상 에이전트 N명이
**처음 보는 파일**을 동시에 `SubmitFile`로 올린다. 판정까지 걸린 시간과 120초(에이전트 대기 한도) 초과 건수를 잰다.

> **용도는 같은 PC에서 코드 변경 전후를 비교하는 것이다.** 절대값은 PC(CPU 세대·Docker VM)마다 다르다.
> 운영에서 몇 초인지는 EC2에서 따로 확인한다. 여기 수치를 운영 수치로 옮겨 적지 않는다.

## 빠른 시작: 300개 한꺼번에 (버스트)

필요한 것: Docker(Mac·Windows는 Docker Desktop, Windows는 WSL2 터미널에서 실행), `make`, `bash`, `gzip`. Python 설치는 필요 없다.
Docker에 CPU 2개·메모리 4GB 이상(권장 CPU 4개·메모리 6GB 이상)을 할당한다.

```bash
# 1) (처음 한 번) 운영과 같은 악성 지문 시드 넣기 — 아래 "운영과 같은 지문 시드" 참고
make loadtest-seed FILE=~/Downloads/malicious-fingerprints-2026-11-19.csv.gz

# 2) 처음 보는 10MB 파일 300개를 같은 순간에 올리고 판정 시간을 잰다
make loadtest-burst                      # 기본 SIZE=10 BURST=300
make loadtest-burst SIZE=1 BURST=50      # 크기·개수 바꾸기
make loadtest-burst BW=50mbit            # 업로드 1건당 회선 제한

make loadtest-down                       # 다 쓰면 정리
```

- 300개 업로드를 모두 연결해 둔 뒤 배리어로 같은 시각에 시작한다. 업로드마다 TCP 연결을 따로 써서
  (한 연결에 몰면 nginx HTTP/2 동시 스트림 한도 128에 걸려 클라이언트에서 줄을 선다) 300개가 실제로 서버에 동시에 들어간다.
- 파일은 공유 기준 버퍼 하나에 업로드마다 앞 32바이트만 다르게 해서 스트리밍한다(300 × 10MB를 미리 만들지 않는다). SHA256이 모두 달라 전부 엔진(YARA)까지 간다.
- 결과: 건수, 성공/실패(오류 코드별), 판정 출처(ENGINE이면 YARA 경로), 첫 판정·마지막 판정(전체 완료)까지 시간,
  p50/p95/p99/max, 10초·120초 초과 수, 전체 처리량, 실제 동시 진행 최대, 클라이언트 메모리. 끝에 DB `analyses`에
  업로드한 SHA마다 엔진 분석 기록이 남았는지 한 번 더 센다.
- 업로드마다 시작·첫 청크 전송·마지막 청크 전송·판정 수신 시각(배리어 기준 초)이 `loadtest-results/<시각>/burst.json`에 남는다.
- **대상은 로컬 스택뿐이다.** 원격 서버(운영 포함)를 가리키는 옵션은 없다.

## 운영과 같은 지문 시드

운영 블랙리스트(MalwareBazaar 일괄 가져오기, 활성 117만 건)와 그 TLSH 지문을 그대로 로컬 DB에 넣는다.
TLSH 유사도 비교 대상 규모가 운영과 같아진다. 파일이 없으면 `make loadtest`는 무작위 지문 `SEED`건(기본 5만)으로 대신한다.

1. 팀 공유 드라이브(링크는 팀 채널에 공유됨)에서 `malicious-fingerprints-2026-11-19.csv.gz`를 브라우저로 받는다
   (드라이브는 로그인·확인 페이지가 있어 자동으로 받지 않는다).
   - 크기 99,256,261바이트, sha256 `19ce12a524859e9683a178b1190cd3a5c56b8357395ab54d40eb5d961692bf44`
   - 운영 위협 데이터라 레포에 커밋하지 않는다(`.gitignore`에 `loadtest-seed/`, `*.csv.gz`).
2. `make loadtest-seed FILE=<받은 경로>`
   - `FILE`을 안 주면 레포 루트의 `loadtest-seed/*.csv.gz`, 다음으로 레포 옆 `../loadtest-seed/*.csv.gz`를 찾는다.
   - 시작 전에 sha256을 확인한다. 다른 버전을 쓰려면 `SEED_SHA256=<값>`.
   - 이 Mac(Docker 8 CPU)에서 전체 161초: COPY 5초, DB 적재 111초, api-server 재기동·반영 확인 17초.

하는 일:

- gzip CSV(`sha256,tlsh,reason,severity`)를 `COPY`로 임시 테이블에 넣고 `INSERT ... SELECT` 한 번으로
  `hash_blacklist`(SHA256, source `IMPORT`, 활성)와 `file_fingerprints`(`CALCULATED`, 앱과 같은 `algorithm_version`,
  `ADMIN_IMPORT`, provenance_detail `MalwareBazaar`, imported_by = 초기 관리자)에 넣는다. 이미 있는 행은 건너뛴다(`ON CONFLICT DO NOTHING`).
  관리 API 일괄 가져오기와 같은 규칙: 유효한 화이트리스트 해시는 넣지 않고, 지문은 활성 블랙리스트 해시에만, tlsh가 빈 행은 블랙리스트만.
- V10 행 트리거는 행마다 advisory 잠금을 잡아 117만 행 한 트랜잭션이면 잠금 표를 넘는다. 그래서 적재 트랜잭션에서만
  `session_replication_role = replica`로 트리거를 끄고, 트리거가 하던 일(`similarity_cache` 비우기, corpus revision 올리기)을 직접 한다.
  감사 로그(`audit_logs`)는 남기지 않는다(로컬 시험 데이터).
- api-server를 재기동한다. 기동 직후 블룸 필터 1차 재구축(`BloomFilterRebuildService`, 블랙리스트 포함)과 메모리 TLSH corpus 적재(`InMemoryTlshCorpus.warmUp`)가 돈다.
  블룸 크기는 운영과 같은 `LOOKUP_BLOOMFILTER_EXPECTEDHASHCOUNT=1500000`이다.
- 반영 확인: 메모리 corpus 크기 지표가 DB의 활성 지문 수와 같은지, 시드의 SHA256 하나가 CheckHash에서 `BLOCK/BLACKLIST`인지,
  시드 TLSH의 마지막 한 자리를 바꾼 변종이 `BLOCK/SIMILARITY`인지 본다. 하나라도 틀리면 실패로 끝난다.
- 앞서 무작위 시드(5만 건)를 넣은 DB라면 그 행도 그대로 남는다. 운영과 똑같이 하려면 먼저 DB 볼륨을 지운다(아래 "한계").

## 사용법 (CONC·RATE)

```bash
# 닫힌 루프(CONC): N명이 한 건 끝나면 다음 건을 올린다
make loadtest                                        # 기본: SIZE=10(MB), CONC=1,2,4,8, 조합당 300건, 회선 제한 없음
make loadtest SIZE=1 CONC=1,4 QUICK=1                # 빠른 확인(조합당 소수 건)
make loadtest SIZE=100 CONC=1,4,8 BW=50mbit COUNT=50
make loadtest SIZE=10,100 CONC=1,4 BW=none,50mbit   # 쉼표로 여러 값을 주면 모든 조합을 잰다

# 개방형 도착(RATE): 초당 정해진 건수를 예정 시각에 시작한다(앞 건이 끝나기를 기다리지 않는다)
make loadtest SIZE=10 RATE=0.5,1,1.5,2 DURATION=180
make loadtest SIZE=10 RATE=1 ARRIVAL=uniform COUNT=300

make loadtest-logs                                   # api-server·엔진 로그
make loadtest-down                                   # 컨테이너 정리(시드한 DB 볼륨은 남긴다)
```

처음 실행은 api-server 이미지 빌드(gradle bootJar)와 지문 5만 건 시드(이 Mac에서 약 3분) 때문에 몇 분 걸린다.
서버는 측정 뒤에도 켜 둔다. 다음 실행은 빌드 캐시를 쓰고 시드를 건너뛰어 빠르다.
코드를 바꾸면 `make loadtest`가 이미지를 다시 빌드해 새 코드로 잰다.

### 옵션

| 변수 | 기본값 | 뜻 |
|---|---|---|
| `SIZE` | `10` | 파일 크기(MB = MiB, 1048576바이트). 쉼표로 여러 개. 소수 가능(`0.5`) |
| `CONC` | `1,2,4,8` | 닫힌 루프: 동시에 올리는 가상 사용자 수. 쉼표로 여러 개. `RATE`와 함께 못 쓴다 |
| `RATE` | (없음) | 개방형 도착: 초당 업로드 시작 건수. 소수 가능, 쉼표로 여러 개. `CONC`와 함께 못 쓴다 |
| `DURATION` | `180` | RATE 측정 시간(초, 워밍업 10초 별도). `QUICK=1`이면 30. `COUNT`를 주면 건수가 우선 |
| `ARRIVAL` | `poisson` | RATE 도착 간격. `poisson`(지수 분포 간격, 실제 사용자처럼 몰렸다 비었다 한다) 또는 `uniform`(1/RATE 고정) |
| `ARRIVAL_SEED` | `1` | 포아송 간격 난수 시드. 같은 값이면 전후 비교 때 같은 도착 순서가 나온다 |
| `MAX_INFLIGHT` | `200` | RATE 동시 진행 상한. 넘는 건은 보내지 않고 "거절"로 센다(PC가 멈추지 않게) |
| `BW` | (없음) | 업로드 1건(가상 사용자 1명)당 속도. `50mbit`, `512kbit`, `1gbit`, `none`. 단위는 tc와 같은 10진(1mbit = 1,000,000bit/s). 쉼표로 여러 개 |
| `COUNT` | `0` | 조합마다 **집계할** 업로드 수(워밍업은 따로 더 올린다). `0`이면 CONC는 300, RATE는 `DURATION`으로 정한다 |
| `QUICK` | (없음) | `QUICK=1`: 빠른 확인. CONC는 조합당 `max(5, 3×CONC)`건, RATE는 30초 |
| `WARMUP` | `-1` | 조합마다 집계에서 뺄 앞 건수. `-1`(기본)이면 CONC는 동시 수만큼, RATE는 처음 10초에 예정된 건. `0`이면 끈다 |
| `SLO` | `10` | 이 초를 넘은 건수(CONC)·비율(RATE)을 따로 센다 |
| `SEED` | `50000` | DB에 넣어 둘 무작위 악성 지문 수. 이미 그만큼 있으면 건너뛴다 |
| `TIMEOUT` | `120` | 업로드 1건의 gRPC 제한(초). 에이전트 대기 한도와 같다 |
| `CPU_SPLIT` | (없음) | `CPU_SPLIT=1`: 서버 컨테이너마다 cpus 상한을 따로 거는 배분(아래 "서버 자원") |
| `BURST` | `300` | `make loadtest-burst`만: 한꺼번에 올릴 파일 수 |
| `FILE` · `SEED_SHA256` | (없음) · 배포 파일 값 | 운영과 같은 지문 시드 파일 경로와 검증값(`make loadtest-seed`, 그리고 `make loadtest`가 시드가 부족할 때) |

### 걸리는 시간(이 Mac 기준, 시드·빌드 제외)

이 Mac(Docker 8 CPU, 서버는 그중 2개)에서 10MB `CONC=8` 처리량은 약 53MB/s(초당 약 5건)였다.
조합 하나의 시간 ≈ (집계 건수 + 워밍업) × 크기 ÷ 처리량. CONC=1은 처리량이 낮아(약 11MB/s 안팎) 더 걸린다.

| 실행 | 예상 |
|---|---|
| `SIZE=10 CONC=8` (300건) | 실측 69초(빌드·준비 포함) |
| `make loadtest` 기본(10MB, CONC=1,2,4,8 × 300건) | 약 10분 |
| `SIZE=100 CONC=8` (300건 = 30GB) | 약 10~20분(처리량이 같다고 볼 때. 100MB는 YARA·TLSH가 더 느려 길어질 수 있다) |
| `SIZE=100 CONC=1,2,4,8` (조합당 300건) | 1시간 안팎. 처음에는 `COUNT=50`이나 `QUICK=1`로 줄여서 본다 |
| `make loadtest-burst` (10MB × 300) | 약 2~3분(실패가 많으면 120초 제한까지 기다린다) |
| `RATE=…` 한 값 | `DURATION` + 10초 + 밀린 업로드가 끝날 때까지(처리량을 넘으면 최대 `TIMEOUT`초 더) |
| `make loadtest-seed` | 실측 161초 |

## 결과 읽는 법

터미널 끝에 표가 나온다. 자세한 값은 `loadtest-results/<UTC 시각>/`에 남는다(gitignore).

### 닫힌 루프(CONC) 표

```
 크기MB 동시 회선  건수 워밍업제외  성공 실패 p50(s) p95(s) p99(s) max(s) 10s초과 120s초과 업로드p50  MB/s
     10    8 none  300          8  300    0   1.42   2.51   3.40   3.52       0        0      0.20 53.40
```

| 열 | 뜻 |
|---|---|
| 건수 / 워밍업제외 | 집계한 건수 / 집계에서 뺀 앞 건수(JIT·연결 수립 등 시작 효과) |
| p50 / p95 / p99 / max | 업로드 시작부터 판정 응답까지(에이전트가 다운로드를 붙잡고 있는 시간). **성공 건만** 센다. 근사 순위(nearest-rank) |
| 10s초과 | `SLO`초를 넘은 건(실패 포함) |
| 120s초과 | `TIMEOUT` 안에 판정을 못 받은 건(DEADLINE_EXCEEDED). 실제 에이전트라면 fail-close로 차단된 다운로드다 |
| 실패 | 120초 초과를 포함한 모든 오류. 오류 문구는 JSON의 `errors`에 있다 |
| 업로드p50 | 마지막 청크를 gRPC에 넘긴 시각의 중앙값. 전송 버퍼만큼 실제 전송 완료보다 조금 이르다 |
| MB/s | 처리량 = 성공 건수 × 크기 ÷ (첫 집계 건 시작부터 마지막 집계 건 끝까지) |

### 개방형 도착(RATE) 표

| 열 | 뜻 |
|---|---|
| 목표/s · 달성/s | 지정한 초당 건수 · 실제로 시작한 초당 건수(포아송은 짧은 구간에서 목표와 조금 다르다) |
| p50~max | **예정 시작 시각부터** 판정 응답까지. 클라이언트가 늦게 보낸 시간도 포함한다(coordinated omission 방지) |
| 10s초과% | `SLO`초를 넘은 비율 |
| 동시max · 동시avg | 시험 중(0.5초마다 표본) 동시에 진행 중인 업로드 수의 최대·평균 |
| 늦은시작 · 최대지연 | 예정보다 0.1초 넘게 늦게 시작한 건수와 가장 늦은 정도. 0이 아니면 **클라이언트가 병목**이다(서버 수치로 읽지 않는다) |
| 거절 | `MAX_INFLIGHT`를 넘어 보내지 않은 건. 0이 아니면 서버가 처리량을 크게 넘겨 받고 있다는 뜻 |
| 시간 흐름 | 표 아래 줄. 집계 구간을 3등분해 `지연 p50 / 최대 지연 / 동시 진행 최대`. 뒤로 갈수록 커지면 처리량을 넘은 것 |

### 파일

- `size{S}mb_c{C}_bw{BW}.json`, `size{S}mb_rate{R}_bw{BW}.json`: 조합별 요약(`summary`)과 업로드 1건씩(`results`: 예정·실제 시작, 걸린 시간, 판정, 오류). RATE는 동시 진행 표본(`inflight_samples`)도 있다
- `summary.json`: 모든 조합 + 실행 환경(git 커밋, 작업 트리 변경 여부, Docker CPU·메모리, 시드 지문 수, 메모리 TLSH corpus 크기)

## CONC와 RATE의 차이

- **닫힌 루프(CONC)**: 사용자 N명이 한 건이 끝나야 다음 건을 보낸다. 서버가 느려지면 보내는 속도도 같이 줄어서
  대기열이 쌓이지 않는다. "동시에 N건일 때 1건이 얼마나 걸리나"와 최대 처리량(MB/s)을 볼 때 쓴다.
  실제 다운로드는 서버 속도를 기다려 주지 않으므로 과부하 때의 지연을 낮게 보여 준다.
- **개방형 도착(RATE)**: 실제처럼 서버 상태와 상관없이 정해진 속도로 업로드가 들어온다. 초당 건수가 처리량 아래면
  지연이 일정하고, 처리량을 넘으면 처리 못 한 업로드가 쌓여 **지연과 동시 진행 수가 시간에 따라 계속 커진다**(결국 120초 초과).
  "초당 몇 건까지 SLO를 지키나"를 볼 때 쓴다.
- 관계(리틀의 법칙): **동시 진행 수 ≈ 초당 건수 × 1건 처리 시간.** 예: 초당 0.5건 × 0.6초 ≈ 0.3건.
  동시 진행 수가 이 값보다 크게, 그리고 시간에 따라 늘어나면 처리량을 넘은 것이다.
- RATE는 업로드마다 새 채널(=TCP 연결)을 연다. 업로드 1건 = 다른 PC 1대로 보고, 회선 제한(BW)도 업로드 1건마다 걸린다.
  연결 수립 시간도 지연에 들어간다.

**전후 비교 방법**: 같은 PC에서, 다른 프로그램을 덜 띄운 상태로, 같은 옵션으로 변경 전 커밋과 변경 후 커밋을 한 번씩(가능하면 번갈아 두 번씩) 돌린다.
같은 조합의 p95와 120s초과를 비교한다. 1~2회 차이가 10% 안쪽이면 잡음으로 본다.

## 무엇을 어떻게 흉내 내나

```
loadtest-client ──(사용자마다 TCP 연결 1개)──▶ toxiproxy ──▶ nginx:80 ──▶ api-server:9090 ──▶ detection-engine
  가상 에이전트 N명                         업로드 속도 제한     gRPC 프록시   SHA·TLSH·스풀·DB      YARA
```

### 서버 자원 (`infra/compose.loadtest.yml`)

**CPU**: 판정 경로 컨테이너(api-server, detection-engine, postgres, redis, nginx) 전부를 같은 CPU 2개(`cpuset: 0,1`)에 묶는다.
서버 전체가 쓰는 CPU 합계가 2.0이고, 그 안에서는 운영 EC2처럼 서로 나눠 쓴다(엔진이 쉬면 api-server·nginx가 더 쓴다).
엔진만 운영 compose.yml과 같이 `cpus: 1.0`으로 묶는다. 클라이언트·toxiproxy·localstack은 나머지 CPU(`2-…`)에 둬서
서버 CPU를 빼앗지 않는다(Docker CPU가 2개뿐이면 같은 CPU를 쓰고 경고를 낸다).

| 컨테이너 | CPU | mem | 근거 |
|---|---|---|---|
| detection-engine | CPU 0,1 중 최대 1.0 | 1024m | 운영 compose.yml 값 그대로 |
| api-server | CPU 0,1 공유 | 1536m | SHA256·TLSH 계산, 스풀 쓰기, gRPC |
| postgres | CPU 0,1 공유 | 768m | 판정 기록·TLSH 후보 조회 |
| nginx | CPU 0,1 공유 | 64m | gRPC 프록시(300건 버스트에서 약 40MB) |
| redis | CPU 0,1 공유 | 128m | 블룸 필터(150만 건 기준 약 2MB) |
| **합계** | **2.0** | **3520m** | 남는 약 370MB는 운영의 OS·모니터링 몫 |

- 처음에는 컨테이너마다 `cpus` 상한(api 0.65, postgres 0.20, nginx 0.10, redis 0.05, 엔진 1.0 = 2.0)을 따로 걸었다.
  300건 버스트에서 nginx가 0.10에 막혀 HTTP/2 SETTINGS를 20초 넘게 못 보내는 등 운영에 없는 병목이 생겨 공유 방식으로 바꿨다.
  그 배분은 `CPU_SPLIT=1`(`infra/compose.loadtest-split.yml`)로 남겨 두었다(운영보다 보수적).
- api-server JVM은 컨테이너 상한을 읽는다. 그대로 두면 힙이 1536m의 25%(384MB)가 되고 CPU를 1개로 본다.
  운영 JVM(상한 없음)은 호스트 3.8GB의 25%(약 950MB)를 힙으로, CPU를 2개로 본다. 그래서
  `-XX:MaxRAMPercentage=60 -XX:ActiveProcessorCount=2`로 힙(약 920MB)과 스레드 구성을 운영에 맞췄다.
- api-server는 `make dev`의 `gradle bootRun`이 아니라 운영과 같은 Dockerfile(bootJar)로 빌드한다.
- 판정 경로 밖(합계에 안 넣음): localstack(운영은 관리형 S3), toxiproxy(회선), loadtest-client.
  운영 EC2에서 도는 Prometheus·exporter·cAdvisor는 띄우지 않는다.

### 회선 제한 (Toxiproxy)

- Docker Desktop에서는 `tc`를 쓸 수 없어 TCP 프록시(Toxiproxy 2.12.0)의 `bandwidth` toxic으로 늦춘다.
- `bandwidth` toxic은 **연결(link)마다** 따로 계산된다. Toxiproxy는 연결마다 toxic 체인(ToxicLink)을 새로 만들고,
  bandwidth는 그 체인 안에서 지나간 바이트만큼 잔다(`toxics/bandwidth.go`, `link.go`). 프록시 전체 합계 제한이 아니다.
- 그래서 가상 사용자마다 gRPC 채널을 따로 열고, gRPC가 같은 대상의 채널끼리 연결을 공유하지 않도록
  `grpc.use_local_subchannel_pool=1`을 준다. 결과적으로 **사용자 1명 = TCP 연결 1개 = 업로드 속도 BW**다.
  RATE는 업로드마다 채널을 새로 열어 **업로드 1건 = TCP 연결 1개 = BW**다.
- 방향은 `upstream`(클라이언트 → 서버, 업로드)만 늦춘다. 판정 응답은 작아서 제한하지 않는다.
- 단위 변환: Toxiproxy `rate`는 KB/s이고 1KB = 1000바이트다(코드가 `바이트 × 1ms ÷ rate`만큼 잔다).
  `BW=50mbit` → 50,000,000bit/s ÷ 8 ÷ 1000 = `rate 6250`(6.25MB/s). 10MiB 파일 업로드는 이론상
  10,485,760 ÷ 6,250,000 ≈ **1.68초**다. rate는 정수라 8kbit 단위로 반올림된다.
- `BW`가 없어도 Toxiproxy를 지난다(toxic만 없음). 조건을 같게 하려는 것이고, 프록시 비용은 아주 작다.

### 파일과 판정 경로

- 파일은 조합마다 무작위 바이트로 한 번 만들고, 업로드마다 **마지막 32바이트**만 바꿔 SHA256이 매번 새롭다
  (판정 캐시·블룸 필터에 안 걸리고 매번 엔진 YARA까지 간다). 원래 스크립트는 앞 32바이트를 바꿨는데,
  끝을 바꾸면 앞부분 해시를 한 번만 계산해 둘 수 있어 클라이언트가 업로드 직전 SHA 계산으로 다른 사용자를 막지 않는다.
- 64KiB 청크로 스트리밍한다(에이전트와 같음). `CheckHash`는 부르지 않고 바로 `SubmitFile`을 부른다
  (처음 보는 파일이면 에이전트도 CheckHash UNKNOWN 뒤 SubmitFile을 부른다. CheckHash 비용은 이 시험에 없다).
- 무작위 파일은 YARA에 안 걸리고(CLEAN) TLSH 유사도 비교까지 간 뒤 `ALLOW/ENGINE`이 된다.
  결과 JSON의 `decisions`로 확인한다.

### 악성 지문 시드

- 운영 일괄 가져오기와 같은 경로를 쓴다: `POST /api/admin/blacklist/import`(1,000건씩, 묶음마다 한 트랜잭션).
  활성 블랙리스트(`hash_blacklist`, source `IMPORT`) + 지문(`file_fingerprints`, `ADMIN_IMPORT`)이 들어가고
  뷰 `active_malicious_fingerprints`에 잡힌다. 그 뷰가 TLSH 비교 대상(메모리 corpus 원본)이다.
- 지문은 고정 시드 난수다(sha256 `^[0-9a-f]{64}$`, tlsh `T1` + 대문자 hex 70자). 다시 돌려도 같은 값이라 서버가 건너뛴다.
  `SEED`보다 적게 있으면 부족한 만큼이 아니라 처음부터 `SEED`건을 보내고, 이미 있는 것은 서버가 건너뛴다.
- TLSH 값이 무작위라 L-value(길이 구간)가 고르게 퍼진다. 실제 악성 표본처럼 특정 길이에 몰리지 않으므로
  L ±5 후보 수가 실제 데이터와 다를 수 있다.
- 대량 삽입은 corpus 토큰(`current_similarity_corpus_token`, V10 트리거)을 바꾼다. 메모리 corpus(`InMemoryTlshCorpus`)는
  토큰이 다르면 DB 경로로 답하면서 백그라운드 재구축을 요청한다(최소 30초 간격). 그래서 측정 전에 작은 파일을 올리며
  actuator 지표 `verdict_similarity_memory_total{result="hit"}`가 늘 때까지(=메모리 corpus가 최신) 기다리고,
  그 크기(`verdict_similarity_memory_size`)를 `summary.json`의 `corpus_memory`에 남긴다.

### 인증

- 로컬은 `infra/dev.env`의 `AUTH_ENABLED=false`라 관리 REST가 로그인 없이 열린다.
- 클라이언트가 `POST /api/admin/agents/enrollment-tokens`로 등록 토큰을 받고, `AgentService/RegisterAgent`로
  가상 에이전트를 등록해 에이전트 토큰을 받는다. 두 토큰은 클라이언트 프로세스 메모리에만 있고 출력·로그·파일에 남지 않는다.
- 실행할 때마다 `loadtest-xxxxxxxx` 에이전트가 하나씩 등록된다(로컬 DB에만 쌓인다).

### gRPC 경로: 운영과 다른 점

- 운영: 에이전트 → **nginx(TLS, 443)** → api-server:9090
- 로컬: 클라이언트 → toxiproxy → **nginx(평문 h2c, 80)** → api-server:9090 (`infra/nginx/nginx.conf`, location은 운영과 같은 `locations.conf`)
- nginx는 지나지만 TLS 암복호화 비용이 없다. 큰 파일·높은 동시성에서 운영 nginx CPU가 더 든다.
- 인터넷 왕복 지연(RTT)은 흉내 내지 않는다. 대역폭만 제한한다.

## 시작 전 점검

`make loadtest`는 시작 전에 Docker에 할당된 자원을 본다(`docker info`).

- CPU 2개 미만이면 멈춘다(4개 이상 권장: 클라이언트·toxiproxy도 같은 VM에서 돈다).
- 메모리 3.5GiB 미만이면 멈춘다. Docker Desktop을 4GB로 설정하면 VM 커널 몫을 빼고 약 3.8GiB로 보여서 하한을 3.5GiB로 뒀다(6GB 이상 권장).
- 설정 위치: Docker Desktop > Settings > Resources. Windows WSL2 백엔드는 `%UserProfile%\.wslconfig`의 `[wsl2]` `processors`·`memory` 후 `wsl --shutdown`.

## 한계

- **절대값은 PC마다 다르다.** CPU 세대, Docker Desktop VM 오버헤드, 디스크(스풀 쓰기), 백그라운드 프로그램이 모두 영향을 준다. 전후 비교에만 쓴다.
- 운영 수치(실제 EC2·TLS·인터넷 회선)는 EC2에서 따로 잰다.
- 서버는 Docker VM의 CPU 0,1에 묶인다. Docker Desktop VM의 CPU 하나는 호스트 코어 하나와 성능이 같지 않을 수 있고, EC2 vCPU(하이퍼스레드 1개)와도 다르다.
- 클라이언트·toxiproxy·localstack은 나머지 CPU를 쓴다. Docker CPU가 2개뿐이면 서버와 같은 CPU를 써서 결과가 느려진다.
- 300건 버스트에서 클라이언트(gRPC 연결 300개)의 최대 RSS는 실행마다 약 270MB~1.4GB였다. 파일 기준 버퍼는 크기만큼 하나뿐이고,
  나머지는 연결별 gRPC(HTTP/2) 버퍼다. Docker 메모리를 6GB 이상 주는 것을 권장한다.
- api-server는 `mem_limit 1536m`에 묶여 있다(운영은 상한 없음). 힙(약 920MB)과 Netty 다이렉트 버퍼(기본 상한 = 최대 힙)를
  합치면 이 상한을 넘을 수 있어, 과부하(300건 버스트·처리량을 넘는 RATE)에서 컨테이너가 OOM으로 죽을 수 있다(2026-11-19 한 번 확인).
  그때는 `make loadtest`나 `make loadtest-burst`를 다시 실행하면 `up -d`가 다시 띄운다. 결과 해석 때 `docker inspect`의 `OOMKilled`를 함께 본다.
- 무작위 시드(`SEED`)는 실제 MalwareBazaar 분포(L-value)와 다르다. 운영 규모는 `make loadtest-seed`(실제 데이터 117만 건)로 맞춘다.
- 시드 데이터·판정 기록은 `platform-loadtest` 프로젝트의 DB 볼륨에 남는다. 처음 상태로 돌리려면
  `docker compose -p platform-loadtest --env-file infra/dev.env -f infra/compose.yml -f infra/compose.temp-db.yml -f infra/compose.loadtest.yml down -v` (볼륨 삭제, 되돌릴 수 없음).
- 판정 기록(file_verdicts 등)이 실행마다 쌓인다. 수천 건 수준에서는 영향이 작지만, 엄밀히 비교하려면 두 커밋을 같은 DB 상태에서 번갈아 돌린다.
