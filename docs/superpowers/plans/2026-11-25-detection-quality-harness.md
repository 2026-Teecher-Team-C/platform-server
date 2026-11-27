# 탐지 품질 측정 하네스 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 운영 서버 `CheckHash`에 신종 악성·정상·변종 파일의 (sha256, tlsh)를 보내고, 서버가 남긴 TLSH 근거로 탐지율·오탐률·변종 탐지율과 임계값 곡선을 계산해 새 임계값을 제안한다.

**Architecture:** `infra/quality/`에 Python 패키지 하나. 데이터 묶음 만들기(`sets`, `mutate`) → 서버 호출(`client`) → 운영 DB에서 근거 뽑기(SQL + ssh 래퍼) → 분석(`analyze`)이 CSV로 이어진다. loadtest처럼 Docker 이미지 안에서 돌리고, 맥북에서 운영으로 보낸다.

**Tech Stack:** Python 3.12, py-tlsh(에이전트와 같은 라이브러리), grpcio 1.84.0 / protobuf 7.36.2(loadtest와 같은 버전), pytest, p7zip, PostgreSQL `\copy`

**Spec:** `project/docs/superpowers/specs/2026-11-25-detection-quality-measurement-design.md` (설계 레포)

## Global Constraints

- 악성 파일은 내려받지 않는다. 악성 쪽은 MalwareBazaar CSV 메타데이터만 쓴다
- 제품 코드(api-server, detection-engine, proto)는 고치지 않는다
- 공개 레포다. MalwareBazaar CSV, 정상 바이너리, 결과 CSV, 블랙리스트 sha 목록, Auth-Key, 관리자 비밀번호, 토큰은 커밋하지 않는다. 출력은 `infra/quality/out/`(gitignore)
- 토큰과 비밀번호는 프로세스 메모리에만 두고 출력·로그·파일에 남기지 않는다(loadtest와 같음)
- 하네스는 운영 주소를 기본값으로 두지 않는다. localhost가 아니면 `--i-know-this-is-prod`가 있어야 실행한다
- 신종 기준 시점: `first_seen_utc >= 2026-11-17 00:00:00 UTC`, tlsh 있음, sha256이 운영 블랙리스트에 없음
- TLSH 형식: `^T1[0-9A-F]{70}$`(서버 `CheckHashQuery.claimedTlsh`와 같음). 형식이 다른 항목은 세고 뺀다
- 정상 파일: 50바이트 이상, py-tlsh가 `TNULL`을 내면 개수만 센다
- 임계값 제안 규칙: 차단 t = B(정상) 중 거리 ≤ t가 0건인 가장 큰 t(0~50). 경고 w = t < w ≤ 70 중 B의 t < 거리 ≤ w 비율이 5% 이하인 가장 큰 w
- 변종 원본 등록 사유: `quality-test-<run_id>`. 측정 직후 전부 해제
- `CheckHash` 동시성 기본 4

## Review Focus

1. MalwareBazaar CSV의 `n/a`·빈 tlsh, 소문자·`T1` 없는 tlsh → 세고 빼야 한다(서버는 형식이 다르면 TLSH 없음으로 처리해 근거가 안 남는다). Task 1 테스트
2. 근거가 없는 요청(거리 70 초과 또는 L ±5 밖) → 분석에서 "매칭 없음(>70)"으로 세야 하며, 누락으로 버리면 탐지율 분모가 줄어 부풀려진다. Task 5 테스트
3. `CheckHash`가 오류(UNAVAILABLE, deadline)로 끝난 항목 → 결과 CSV에 오류로 남고 분석 분모에서 빠지되, 오류 건수를 결과에 따로 보여야 한다. Task 3·5 테스트
4. 정상 파일 중 운영 블랙리스트·화이트리스트에 이미 있는 것 → 응답 source가 BLACKLIST/WHITELIST. TLSH 곡선에 넣지 않고 따로 센다. Task 5 테스트
5. 변종 원본 해제가 일부 실패 → 해제 명령이 실패 건수를 보여 주고 0이 아니면 종료 코드 1. Task 3 테스트

---

## File Structure

| 파일 | 책임 |
|---|---|
| `infra/quality/Dockerfile` | python 3.12 + grpc + py-tlsh + p7zip + pytest, proto 생성 |
| `infra/quality/quality/sets.py` | MalwareBazaar CSV → M, 정상 폴더 → B 레코드 |
| `infra/quality/quality/mutate.py` | 변형 함수와 V 레코드 |
| `infra/quality/quality/client.py` | 대상 가드, 관리자 로그인, 에이전트 등록, CheckHash 실행, 정책 조회·변경, 변종 원본 등록·해제 |
| `infra/quality/quality/analyze.py` | 응답 + 근거 조인, 곡선, 제안, 표, 예측 대조, Markdown 출력 |
| `infra/quality/quality/cli.py` | 하위 명령 |
| `infra/quality/sql/*.sql`, `infra/quality/prod_sql.sh` | 운영 DB에서 블랙리스트 sha·근거 내보내기, 정리 |
| `infra/quality/benign-manifest.csv`, `infra/quality/fetch_benign.sh` | 정상 파일 목록, 내려받기·검증·풀기 |
| `infra/quality/tests/` | pytest |
| `infra/quality/README.md` | 실행 순서(런북) |
| `Makefile` | `test-quality`, `quality` 대상 |

### 공통 레코드 (모든 Task가 쓰는 CSV 열)

- `Item`: `item_id: str, set: Literal["M","B","V"], sha256: str, tlsh: str, file_size: int, family: str, file_type: str, variant: str, origin_sha256: str` → `items-<set>.csv`
- `Response`: `item_id, event_id, decision, source, reason, error, pass_id` → `responses-<pass_id>.csv`
- `Evidence`: `event_id, distance, matched_sample_sha256, classification, policy_version, matched_label` → `evidence-<pass_id>.csv`

---

### Task 1: 패키지 뼈대와 신종 악성 묶음(M)

**Files:**
- Create: `infra/quality/Dockerfile`, `infra/quality/quality/__init__.py`, `infra/quality/quality/sets.py`, `infra/quality/quality/records.py`, `infra/quality/tests/test_sets_malicious.py`, `infra/quality/.gitignore`(`out/`)
- Modify: `Makefile` (`test-quality:` → 이미지 빌드 후 컨테이너에서 `pytest infra/quality/tests`)

**Interfaces:**
- Produces: `records.Item`(dataclass, 위 열), `records.write_items(path, items)`, `records.read_items(path) -> list[Item]`
- Produces: `sets.select_malicious(lines: Iterable[str], blacklist: set[str], since: datetime) -> tuple[list[Item], Counter]`. Counter 키: `old`, `no_tlsh`, `bad_tlsh`, `in_blacklist`, `bad_sha`, `duplicate`, `selected`

- [ ] **Step 1: 실패 테스트 작성** (`test_sets_malicious.py`. MalwareBazaar 형식 문자열 픽스처: `# "first_seen_utc","sha256_hash",...,"file_type_guess",...,"signature",...,"tlsh"` 주석 헤더 + 값 따옴표)
  - `test_고르는_조건`: 2026-11-16 23:59:59 행 → `old`, 2026-11-17 00:00:00 행 → 선택, tlsh `n/a` → `no_tlsh`, 소문자 tlsh → `bad_tlsh`, blacklist에 있는 sha → `in_blacklist`, 같은 sha 두 번 → `duplicate` 1
  - `test_레코드_값`: 선택된 Item의 `set == "M"`, `family == signature`(n/a면 `""`), `file_type == file_type_guess`, `item_id == "M-" + sha256[:12]`
- [ ] **Step 2: 실행해 실패 확인** — `make test-quality` → `ModuleNotFoundError: quality.sets`
- [ ] **Step 3: 구현** — CSV 읽기는 `import_malwarebazaar.iter_entries`와 같은 방식(주석 줄에서 열 이름, `csv.reader`, 스트리밍). file_size 열(`file_size`)이 없으면 0
- [ ] **Step 4: 통과 확인** — `make test-quality` PASS
- [ ] **Step 5: 커밋** — `git commit -m "test(quality): 신종 악성 묶음 선택과 하네스 뼈대"`

### Task 2: 정상 묶음(B)과 변종 묶음(V)

**Files:**
- Create: `infra/quality/quality/mutate.py`, `infra/quality/benign-manifest.csv`, `infra/quality/fetch_benign.sh`, `infra/quality/tests/test_sets_benign.py`, `infra/quality/tests/test_mutate.py`
- Modify: `infra/quality/quality/sets.py`

**Interfaces:**
- Produces: `sets.scan_benign(root: Path, blacklist: set[str]) -> tuple[list[Item], Counter]` (Counter: `too_small`, `tnull`, `in_blacklist`, `selected`; `in_blacklist`도 Item으로 내되 `variant="in_blacklist"` 표시)
- Produces: `mutate.MUTATIONS: dict[str, Callable[[bytes, random.Random], bytes]]` 키 정확히 `append-1k`, `append-64k`, `patch-0.1%`, `patch-1%`, `patch-5%`, `string-edit`, `truncate-1%`
- Produces: `mutate.build_variants(originals: list[Path], seed: int) -> list[Item]` (원본 Item `variant="origin"` + 변형 Item, `origin_sha256` 채움)
- Produces: `sets.pick_variant_origins(items: list[Item], root: Path, count: int = 50, seed: int) -> list[Path]` — 설치 파일 자체(매니페스트에 있는 파일명)는 제외, PE(`MZ` 시작)만, 크기 4구간(<100KB, <1MB, <10MB, ≥10MB)에서 고르게

- [ ] **Step 1: 실패 테스트**
  - `test_scan_benign`: tmp 폴더에 49바이트 파일 → `too_small`, 같은 바이트 반복 1KB → `tnull`, 무작위 4KB → 선택(tlsh가 `T1`+70 hex, sha256 일치, `set == "B"`)
  - `test_mutations`: 각 변형이 원본과 sha가 다르고, `append-1k` 길이 +1024, `truncate-1%` 길이 `len - len//100`, `patch-1%` 길이 같고 다른 바이트 수 ≤ `len//100`, `string-edit`는 길이 같음. 같은 seed면 같은 결과
  - `test_pick_variant_origins`: 설치 파일 이름은 안 나오고, MZ 아닌 파일은 안 나옴
- [ ] **Step 2: 실패 확인** — `make test-quality`
- [ ] **Step 3: 구현** — `string-edit`: 4바이트 이상 ASCII 출력 가능 문자열 중 최대 5개의 각 첫 글자를 다른 출력 가능 문자로. 없으면 `patch-0.1%`와 같은 동작으로 대체하지 말고 그 변형을 건너뛴다(건수에 남김)
- [ ] **Step 4: 매니페스트와 받기 스크립트** — `benign-manifest.csv` 열 `name,version,url,sha256`. 공식 배포처 Windows 설치 파일 30개(7-Zip, Git for Windows, Python, Node.js, VLC, Notepad++, PuTTY, LibreOffice, Firefox, VS Code, WinSCP, KeePass, Audacity, GIMP, Inkscape, OBS Studio, HandBrake, FileZilla, Wireshark, Blender, CMake, Go, Rustup, PowerToys, Everything, ShareX, qBittorrent, Thunderbird, VirtualBox, Zoom 중 받을 수 있는 것). `fetch_benign.sh OUT_DIR`: 받기 → sha256 대조(다르면 그 파일만 실패로 표시) → `7z x`로 `OUT_DIR/extracted/<name>/`에 풀기(실패하면 설치 파일만 남김). 결과 파일 수 출력
- [ ] **Step 5: 통과 확인 + 실제 받기** — `make test-quality` PASS, `bash infra/quality/fetch_benign.sh infra/quality/out/benign` 실행 후 파일 수 ≥ 1,000 확인. 모자라면 매니페스트에 추가
- [ ] **Step 6: 커밋** — `git commit -m "test(quality): 정상·변종 묶음 생성"`

### Task 3: 서버 클라이언트

**Files:**
- Create: `infra/quality/quality/client.py`, `infra/quality/tests/test_client.py`

**Interfaces:**
- Consumes: `records.Item`
- Produces: `client.check_target(http_url: str, grpc_target: str, prod_flag: bool) -> None` (localhost/127.0.0.1/::1/compose 서비스 이름 `nginx`가 아니면 prod_flag 필요, 아니면 `SystemExit(2)`)
- Produces: `client.Admin(http_url)` with `.login(user, password)`, `.get_policy() -> tuple[int,int]`, `.put_policy(block, warn)`, `.enrollment_token() -> str`, `.import_blacklist(items, label) -> dict`, `.release_blacklist(shas) -> tuple[int, list[str]]`(성공 수, 실패 sha)
- Produces: `client.register_agent(grpc_target, enrollment_token, tls: bool) -> tuple[str, str]`(agent_id, agent_token)
- Produces: `async client.run_checks(grpc_target, tls, agent_token, items, pass_id, concurrency=4, timeout=30) -> list[Response]` — 항목마다 새 `event_id=uuid4()`, `CheckHashRequest(sha256, file_size, tlsh, event_id)`. gRPC 오류는 `error=<status code name>`으로 남기고 계속

- [ ] **Step 1: 실패 테스트** (in-process 가짜 gRPC 서버 + `http.server` 가짜 REST)
  - `test_운영_가드`: `check_target("https://example.com", "example.com:443", False)` → SystemExit, `localhost`는 통과, 플래그 있으면 통과
  - `test_run_checks`: 가짜 서버가 sha 접두 `aa` → BLOCK/SIMILARITY, `bb` → UNAVAILABLE 오류, 나머지 UNKNOWN. 결과 `decision`·`source`·`error` 열이 맞고, 모든 `event_id`가 서로 다르며, 요청의 `tlsh`·`file_size`가 Item 값 그대로
  - `test_해제_실패_보고`: 가짜 REST가 sha 하나에 500 → `release_blacklist`가 `(n-1, [그 sha])`
  - `test_토큰_안남김`: `run_checks` 중 출력(capsys)에 토큰 문자열이 없음
- [ ] **Step 2: 실패 확인**
- [ ] **Step 3: 구현** — 로그인 `POST /api/admin/auth/login` → access 토큰을 `Admin` 인스턴스에만. 등록 `POST /api/admin/agents/enrollment-tokens` → `AgentService/RegisterAgent`(loadtest `register_agent`와 같은 요청 필드). 정책 `GET/PUT /api/admin/similarity-policy`. 등록 `POST /api/admin/blacklist/import`(1,000건씩, `source="quality-test"`, label=`quality-test-<run_id>`), 해제 `DELETE /api/admin/blacklist/{sha256}`. 운영 gRPC는 TLS(`grpc.ssl_channel_credentials()`)
- [ ] **Step 4: 통과 확인**
- [ ] **Step 5: 커밋** — `git commit -m "test(quality): CheckHash 측정 클라이언트"`

### Task 4: 운영 DB 내보내기·정리 SQL

**Files:**
- Create: `infra/quality/sql/blacklist_shas.sql`, `infra/quality/sql/export_evidence.sql`, `infra/quality/sql/cleanup_preview.sql`, `infra/quality/sql/cleanup.sql`, `infra/quality/prod_sql.sh`, `infra/quality/tests/test_sql.py`

**Interfaces:**
- Produces: `prod_sql.sh <sql-file> [psql -v 변수...]` → ssh 별칭(기본 `teecher`, `QUALITY_SSH_HOST`로 변경)에서 `docker exec -i platform-postgres-1 psql -At`로 실행, stdout을 그대로 출력
- `export_evidence.sql` (`:agent_id` 변수): `\copy (SELECT e.event_id, a.distance, a.matched_sample_sha256, a.classification, a.policy_version, b.reason AS matched_label FROM event_decision_evidence e LEFT JOIN similarity_assessments a USING (assessment_id) LEFT JOIN hash_blacklist b ON b.sha256 = a.matched_sample_sha256 WHERE e.agent_id = :'agent_id') TO STDOUT WITH CSV HEADER`
- `cleanup_preview.sql`/`cleanup.sql` (`:agent_id`): 그 에이전트의 `event_decision_evidence`와 거기 연결된 `similarity_assessments`(input_source='AGENT_CLAIMED'), `download_events` 건수 출력 / 한 트랜잭션에서 외래 키 순서로 삭제 후 에이전트 상태를 비활성으로. 감사 로그는 건드리지 않음

- [ ] **Step 1: 실패 테스트** — `test_sql.py`가 Testcontainers 대신 로컬 loadtest 스택 Postgres에 붙지 않고, **api-server 마이그레이션을 적용한 임시 Postgres**(이미지 `postgres:17-alpine` + `api-server/src/main/resources/db/migration/*.sql`을 버전 순서로 실행)에서: 에이전트 2개, 근거 행(거리 있음 1, 없음 1) 넣기 → `export_evidence`가 대상 에이전트 행 2개만, 거리 없는 행은 distance 빈칸 / `cleanup` 후 대상 에이전트 행 0, 다른 에이전트 행 유지
- [ ] **Step 2: 실패 확인**
- [ ] **Step 3: SQL 작성** — 테이블·열 이름은 V1, V9, V11, V13 마이그레이션 기준으로 확인한다
- [ ] **Step 4: 통과 확인**
- [ ] **Step 5: 커밋** — `git commit -m "test(quality): 운영 근거 내보내기와 정리 SQL"`

### Task 5: 분석

**Files:**
- Create: `infra/quality/quality/analyze.py`, `infra/quality/tests/test_analyze.py`

**Interfaces:**
- Consumes: `Item`, `Response`, `Evidence` CSV
- Produces: `analyze.join(items, responses, evidence) -> list[Row]` (`Row`: Item 열 + decision, source, error, distance: int | None, classification, matched_label)
- Produces: `analyze.curve(rows, set_name) -> list[tuple[int, float, int]]` (t = 0..70마다 (t, 비율, 건수). 분모는 error 없고 source가 BLACKLIST/WHITELIST가 아닌 행. distance None은 어떤 t에도 안 들어감)
- Produces: `analyze.propose(rows) -> Proposal(block: int, warn: int, block_clipped: bool, notes: list[str])`
- Produces: `analyze.predict_mismatches(rows_pass2, block) -> list[str]`(item_id): 분모 조건을 만족하는 행 중 `(distance is not None and distance <= block)`와 `decision == "DECISION_BLOCK" and source == "DECISION_SOURCE_SIMILARITY"`가 다른 것
- Produces: `analyze.report(rows, proposal, meta: dict) -> str` (Markdown: 데이터 규모와 제외 건수, M/B 차단·경고율(현재 30/70과 제안값), 곡선 표(t=0,5,…,70), 상위 10 패밀리 차단율과 "최근접 라벨 = 패밀리" 비율, 파일 형식별, 변종 표(변형별 SHA 탐지 0% 확인 + TLSH 차단·경고율), 오류 건수, 결정 문장)

- [ ] **Step 1: 실패 테스트**
  - `test_분모`: error 행, source BLACKLIST 행은 곡선 분모에서 빠지고 `report`의 제외 표에 건수로 나옴
  - `test_매칭없음`: distance None인 M 행은 t=70에서도 미탐지로 셈(분모에 포함)
  - `test_제안_규칙`: B 거리 [12, 40, 41, 66], M 다수 → block = 11. B 31~70 경고 비율이 5% 이하가 되는 가장 큰 w 계산(픽스처로 정답 고정). B 최소 거리가 60이면 block = 50, `block_clipped = True`
  - `test_예측_대조`: 거리 20·block 25인데 응답이 UNKNOWN이면 그 item_id 반환
  - `test_결정_문장`: report에 "차단을 30에서 {block}로 바꾸면" 문장이 있고 M 증감 %p와 B 경고 건수 증감이 숫자로 들어감
- [ ] **Step 2: 실패 확인**
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인**
- [ ] **Step 5: 커밋** — `git commit -m "test(quality): 탐지 품질 분석과 임계값 제안"`

### Task 6: CLI, Make 대상, 런북, 로컬 시험 실행

**Files:**
- Create: `infra/quality/quality/cli.py`, `infra/quality/README.md`, `infra/quality/tests/test_cli.py`
- Modify: `Makefile` (`quality:` → 컨테이너에서 `python -m quality.cli $(ARGS)`, `out/`과 `~/.config/abusech` 읽기 전용 마운트)

**Interfaces:**
- 하위 명령: `build-malicious --csv --blacklist-shas --out`, `build-benign --root --blacklist-shas --out`, `build-variants --root --items-b --count 50 --seed --out`, `run --target-http --target-grpc --tls --items ... --pass-id [--i-know-this-is-prod]`(관리자 계정은 `QUALITY_ADMIN_USER`/`QUALITY_ADMIN_PASSWORD` 환경 변수, 등록한 agent_id를 `out/agent-<pass_id>.txt`에 기록), `policy get|set BLOCK WARN`, `variants register|release --items-v --run-id`, `analyze --items ... --responses ... --evidence ... --out report.md`
- [ ] **Step 1: 실패 테스트** — `test_cli`: 대상이 운영인데 플래그 없으면 종료 코드 2, `variants release`가 실패 건수 > 0이면 종료 코드 1, `analyze`가 픽스처 CSV로 report.md를 만든다
- [ ] **Step 2: 실패 확인**
- [ ] **Step 3: 구현과 README 런북** — README에 설계 5장·5-1·5-2 순서를 명령어로 옮긴다(백업 `pg_dump -Fc` → 맥북 복사, 팀 공지, 머지 동결, 정책 확인, 1회차, 근거 내보내기, 제안값으로 정책 변경, 2회차, 예측 대조, 정책 복구, 변종 등록 → 측정 → 해제, 정리 미리보기 → 정리, 결과 문서)
- [ ] **Step 4: 통과 확인** — `make test-quality` 전체 PASS
- [ ] **Step 5: 로컬 시험 실행** — `make loadtest-seed`가 된 로컬 스택에서 M 200건, B 200건, V 원본 5개로 런북 전체를 돈다(로컬은 플래그 없이). 확인: 근거 행 수 = 거리 70 이하 응답 수, 예측 대조 불일치 0, 해제 후 등록 건수 = 해제 건수, 정리 후 그 에이전트 행 0
- [ ] **Step 6: 커밋** — `git commit -m "feat(quality): 탐지 품질 측정 CLI와 런북"`

### Task 7: 운영 측정과 결과 문서 (사람 확인 필요)

운영을 바꾸는 단계마다 실행 전에 사용자 확인을 받는다: 백업, 팀 공지 문구, 정책 변경, 변종 등록, 정리.

- [ ] **Step 1: 측정 전 점검** — 설계 5-1 표 항목을 실행하고 값을 기록. 디스크 여유 ≥ 2GB, 실사용 에이전트 수, corpus 구축 로그
- [ ] **Step 2: 데이터 준비** — `prod_sql.sh sql/blacklist_shas.sql > out/blacklist-shas.txt`, MalwareBazaar 전체 덤프 받기, `build-malicious`, `build-benign`, `build-variants`. 제외 건수 기록. M에 운영 블랙리스트 sha가 0건인지 확인
- [ ] **Step 3: 1회차(30/70)** — `run` M+B, `export_evidence` → `analyze`로 제안값 산출
- [ ] **Step 4: 2회차(제안값)** — 사용자 확인 후 `policy set`, `run` M+B, `predict_mismatches` 0건 확인, `policy set 30 70` 복구 후 `policy get`으로 확인
- [ ] **Step 5: 변종** — 사용자 확인 후 `variants register` → `run` V → `variants release`(실패 0) → 근거 내보내기
- [ ] **Step 6: 정리** — `cleanup_preview` 건수 확인 → 사용자 확인 → `cleanup`. 정책 30/70, 가짜 악성 0건 재확인
- [ ] **Step 7: 결과 문서** — `report.md`에 측정 일시, 운영 버전(`git log -1`), corpus 크기, 데이터 출처·한계("합성 변종, 메타데이터 기반, 실사용 트래픽 아님")를 붙여 `project/docs/advancement/2026-11-xx-detection-quality-results.md`로 저장
