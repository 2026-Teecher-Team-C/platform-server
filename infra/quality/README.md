# 탐지 품질 측정 하네스

운영 서버 `CheckHash`에 신종 악성·정상·변종 파일의 (sha256, tlsh)를 보내고, 서버가 남긴 TLSH 근거로
탐지율·오탐률·변종 탐지율과 임계값 곡선을 계산한다. 설계: 설계 레포
`docs/superpowers/specs/2026-11-25-detection-quality-measurement-design.md`.

- 악성 파일은 내려받지 않는다. 악성 쪽은 MalwareBazaar 메타데이터(SHA-256, TLSH, 패밀리)만 쓴다
- 측정 데이터와 결과 파일은 `infra/quality/out/`에만 둔다. **커밋하지 않는다**(공개 레포). 운영 DB의 측정 기록은 남긴다(6장)
- 관리자 계정은 `QUALITY_ADMIN_USER` / `QUALITY_ADMIN_PASSWORD` 환경 변수로만 넘긴다. 토큰은 출력·파일에 남지 않는다
- localhost가 아닌 대상은 `--i-know-this-is-prod`가 있어야 보낸다

```bash
make test-quality                      # 하네스 테스트
make quality ARGS="<명령> ..."          # 컨테이너 안에서 python -m quality.cli 실행 (작업 폴더 = infra/quality)
```

아래에서 `PROD`는 `--http https://<운영 도메인> --i-know-this-is-prod`, `PRODG`는 `--grpc <운영 도메인>:443 --tls`를 뜻한다.
운영 도메인은 `infra/nginx/nginx.prod.conf`의 `server_name`이다.

## 1. 측정 전 (운영)

| 항목 | 명령·기준 |
|---|---|
| 팀 공지 | 측정 시간대, "임계값이 잠깐 바뀌고 일부 내부 파일이 잠깐 차단될 수 있다"를 팀 채널에 알린다 |
| 시간대 | EC2가 매일 01:00 KST쯤 꺼진다. 낮에 시작한다 |
| 머지 동결 | develop에 머지하면 운영이 자동 배포·재기동된다. 측정 동안 머지하지 않는다 |
| 백업 | `ssh teecher 'docker exec platform-postgres-1 pg_dump -Fc -U <user> <db>' > out/prod-before-quality.dump` |
| 디스크 | `ssh teecher df -h /` 여유 2GB 이상 |
| 상태 기록 | `bash prod_sql.sh` 로 활성 지문 수, 최근 하루 하트비트 에이전트 수를 적는다. 메모리 corpus 크기는 api-server 로그의 `TLSH 메모리 corpus 구축 ... size=` |

## 2. 데이터 준비

```bash
bash infra/quality/prod_sql.sh infra/quality/sql/blacklist_shas.sql > infra/quality/out/blacklist-shas.txt
# MalwareBazaar 전체 덤프(full.csv.zip)를 out/에 받는다 (infra/scripts/import_malwarebazaar.py와 같은 export, Auth-Key 필요)
make quality ARGS="build-malicious --csv out/full.csv.zip --blacklist-shas out/blacklist-shas.txt"
docker run --rm -v "$PWD/infra/quality":/app --entrypoint bash platform-quality /app/fetch_benign.sh /app/out/benign
cp infra/quality/out/benign/items-B.csv infra/quality/out/items-B.csv
make quality ARGS="build-variants --blacklist-shas out/blacklist-shas.txt"   # 내부 DLL만, 설치 파일과 TLSH 70 이하는 제외
```

`build-malicious` 출력의 `in_blacklist`(운영 블랙리스트와 겹쳐 뺀 수)를 결과 문서에 적는다.

## 3. 1회차 (현재 정책 30/70)

```bash
make quality ARGS="policy get PROD"                                # 30/70인지 확인
make quality ARGS="run PROD PRODG --items out/items-M.csv out/items-B.csv --pass-id p1"
bash infra/quality/prod_sql.sh infra/quality/sql/export_evidence.sql agent_id=$(cat infra/quality/out/agent-p1.txt) \
    > infra/quality/out/evidence-p1.csv
make quality ARGS="analyze --items out/items-M.csv out/items-B.csv --responses out/responses-p1.csv \
    --evidence out/evidence-p1.csv --out out/report-p1.md"
```

## 4. 2회차 (제안 정책) — 실행 전 확인 받기

정책은 측정이 끝나는 즉시 되돌린다. 대조(predict)에서 문제가 나와도 운영이 제안값에 머물지 않게 하기 위해서다.

```bash
make quality ARGS="policy set PROD --block <제안 차단> --warn <제안 경고>"
make quality ARGS="run PROD PRODG --items out/items-M.csv out/items-B.csv --pass-id p2"
make quality ARGS="policy set PROD --block 30 --warn 70"           # run이 실패해도 바로 이 줄을 실행한다
bash infra/quality/prod_sql.sh infra/quality/sql/export_evidence.sql agent_id=$(cat infra/quality/out/agent-p2.txt) \
    > infra/quality/out/evidence-p2.csv
make quality ARGS="predict --items out/items-M.csv out/items-B.csv --base-responses out/responses-p1.csv \
    --base-evidence out/evidence-p1.csv --responses out/responses-p2.csv --evidence out/evidence-p2.csv \
    --block <제안 차단> --warn <제안 경고>"                          # 불일치 0건이어야 함(차단·경고 모두, 빠진 항목 포함)
```

## 5. 변종 — 실행 전 확인 받기

```bash
make quality ARGS="variants register PROD --run-id r1"             # 원본만 등록. 전부 새로 추가돼야 registered-r1.txt를 남긴다
make quality ARGS="run PROD PRODG --items out/items-V.csv --pass-id v1"
make quality ARGS="variants release PROD --run-id r1"              # registered-r1.txt에 적힌(새로 추가된) 원본만 해제
bash infra/quality/prod_sql.sh infra/quality/sql/export_evidence.sql agent_id=$(cat infra/quality/out/agent-v1.txt) \
    > infra/quality/out/evidence-v1.csv
```

## 6. 마무리 (기록은 남긴다) — 실행 전 확인 받기

측정 기록(근거·평가, TLSH 차단 건의 다운로드 이벤트, 감사 로그)은 운영에 남겨 나중에 다시 분석한다.
`run`은 TLSH로 차단된 항목만 `ReportEvent`로 남긴다(호스트 `quality-test.local`, 파일명 `quality-<묶음>-<패밀리>-<item_id>`).
UNKNOWN은 실제라면 업로드·YARA로 이어지는데 파일이 없어 하지 못하므로 이벤트를 만들지 않는다. 끄려면 `--no-report`.

측정 에이전트는 토큰만 다시 못 쓰게 막는다. `revoke_agent.sql`은 `agent_version = 'quality-harness'`가 아니면 멈춘다:

```bash
for p in p1 p2 v1; do bash infra/quality/prod_sql.sh infra/quality/sql/revoke_agent.sql agent_id=$(cat infra/quality/out/agent-$p.txt); done
```

측정 데이터를 지워야 할 때만 `cleanup_preview.sql` → `cleanup.sql`을 같은 방식으로 쓴다(같은 가드가 있다).

## 7. YARA 보완 측정

TLSH가 놓친 신종 악성을 운영 YARA 룰셋(활성 v3)이 얼마나 잡는지, 정상 파일을 얼마나 막는지 잰다. 악성 샘플은
Azure VM에서만 받고(zip째 메모리로 읽어 디스크에 풀지 않는다) 결과 CSV만 맥북으로 가져온다.

```bash
# 함수로 정의한다(bash·zsh 모두 동작, ~/.ssh/config는 건드리지 않는다)
vm()   { ssh -i ~/.ssh/yara-test2_key.pem azureuser@4.252.1.151 "$@"; }
vmcp() { scp -i ~/.ssh/yara-test2_key.pem "$@"; }

# (맥북) 룰셋 내보내기. 운영 EC2는 매일 01:00 KST에 꺼진다. 룰 수 5,091개와 sha256을 기록한다
bash infra/quality/prod_sql.sh infra/quality/sql/active_ruleset_source.sql > infra/quality/out/ruleset-v3.yar
bash infra/quality/prod_sql.sh infra/quality/sql/active_disabled_rules.sql > infra/quality/out/disabled.txt
grep -cE '^(private |global )*rule ' infra/quality/out/ruleset-v3.yar; shasum -a 256 infra/quality/out/ruleset-v3.yar

# VM에서 docker가 sudo 없이 되는지 확인한다. 안 되면 azureuser를 docker 그룹에 방금 넣은 것이므로
# `exit`로 나갔다가 다시 접속한 뒤 다시 확인한다
vm 'docker ps'

# 하네스 이미지를 VM에서 빌드
# 커밋 전 변경도 들어가도록 작업 트리를 보낸다(out/·samples/ 제외). macOS tar의 ._ 파일은 COPYFILE_DISABLE로 막는다
COPYFILE_DISABLE=1 tar -c --exclude='infra/quality/out' --exclude='infra/quality/samples' infra/quality proto | vm 'mkdir -p ~/h && tar -x -C ~/h'
vm 'docker build -f ~/h/infra/quality/Dockerfile -t platform-quality ~/h'
# tar가 out/을 뺐으므로 맥북에서 만든 입력 세 개를 따로 보낸다
vm 'mkdir -p ~/h/infra/quality/out'
vmcp infra/quality/out/items-M.csv infra/quality/out/ruleset-v3.yar infra/quality/out/disabled.txt \
  azureuser@4.252.1.151:h/infra/quality/out/

# 스모크(VM): 3건만 받아 스캔해 본 뒤 전체로 간다. fetch가 ok=3이고 yara 행이 모두 status ok여야 한다
head -4 infra/quality/out/items-M.csv > infra/quality/out/items-smoke.csv
vmcp infra/quality/out/items-smoke.csv azureuser@4.252.1.151:h/infra/quality/out/
tr -d '[:space:]' < ~/.config/abusech/auth-key | vm 'read -r ABUSECH_AUTH_KEY; export ABUSECH_AUTH_KEY; cd ~/h/infra/quality && \
  docker run --rm -e ABUSECH_AUTH_KEY -v $PWD:/app platform-quality fetch-samples --items out/items-smoke.csv --dest samples-smoke'
vm 'cd ~/h/infra/quality && docker run --rm --cpus=1 --memory=1g -v $PWD:/app platform-quality yara-scan \
  --rules out/ruleset-v3.yar --disabled out/disabled.txt --items out/items-smoke.csv --zips samples-smoke --out out/yara-smoke.csv \
  && cat out/yara-smoke.csv'
# 위가 통과한 뒤에만 아래 전체 받기를 한다(smoke 샘플은 `sudo rm -rf samples-smoke out/yara-smoke.csv`로 지운다(docker가 root로 만든 파일))

# 샘플 받기(VM). 키는 표준 입력으로만 넘기고 출력·파일에 남기지 않는다. 다시 돌리면 받은 것은 건너뛴다
tr -d '[:space:]' < ~/.config/abusech/auth-key | vm 'read -r ABUSECH_AUTH_KEY; export ABUSECH_AUTH_KEY; cd ~/h/infra/quality && \
  docker run --rm -e ABUSECH_AUTH_KEY -v $PWD:/app platform-quality fetch-samples --items out/items-M.csv --dest samples'

# 악성 스캔(VM): shard 3개 동시, 컨테이너당 1 CPU·1GB
vm 'docker rm -f yara-M-0 yara-M-1 yara-M-2 2>/dev/null; cd ~/h/infra/quality && for i in 0 1 2; do docker run -d --name yara-M-$i --cpus=1 --memory=1g -v $PWD:/app platform-quality \
  yara-scan --rules out/ruleset-v3.yar --disabled out/disabled.txt --items out/items-M.csv --zips samples \
  --out out/yara-M-$i.csv --shard $i/3; done'
# 끝나기를 기다린 뒤 종료 코드 0과 OOMKilled false를 확인한다(하나라도 아니면 가져가지 말고 로그를 본다)
vm 'docker wait yara-M-0 yara-M-1 yara-M-2; for i in 0 1 2; do
  docker inspect -f "yara-M-$i exit={{.State.ExitCode}} oom={{.State.OOMKilled}}" yara-M-$i; done'

# 결과만 맥북으로(악성 zip은 가져오지 않는다)
vmcp 'azureuser@4.252.1.151:h/infra/quality/out/yara-M-*.csv' infra/quality/out/

# 정상 B 스캔(맥북): 이미지를 맥북에서도 빌드한다(make quality와 같은 빌드)
docker build -q -f infra/quality/Dockerfile -t platform-quality . >/dev/null
docker run --rm --cpus=1 --memory=1g -v "$PWD/infra/quality":/app --entrypoint bash platform-quality \
  scan_benign_yara.sh out/benign out/ruleset-v3.yar out/disabled.txt

# 보고서
make quality ARGS="yara-report --items out/items-M.csv out/items-B.csv --responses out/responses-p1.csv \
  --evidence out/evidence-p1.csv --yara out/yara-M-0.csv out/yara-M-1.csv out/yara-M-2.csv out/benign/yara-B.csv --out out/report-yara.md"
```

- `fetch-samples`는 `ok/missing/error/skipped` 개수를 출력하고 `samples/fetch-status.csv`를 남긴다. `error`가 1%를 넘으면 다시 돌린다
- `yara-scan --zips`는 이어 하기를 지원한다: `--out`에 있는 item_id는 건너뛰고 100건마다 `--out`을 원자적으로 갱신한다. 읽는 중인 item_id를
  `<out>.inflight`에 적어 두므로, 컨테이너가 OOM으로 죽었다면 같은 명령을 다시 돌리면 그 항목은 `error`로 기록되고 넘어간다
- `yara-scan --shard I/N`은 item_id순 `index % N == I`만 처리한다. 세 shard 줄 수 합이 items 수와 같아야 하고 `docker ps -a`에 OOMKilled가 없어야 한다
- `--files`는 이번 DIR에서 찾은 item만 쓰고(`--out`이 있으면 덧붙이되 같은 item_id는 다시 쓰지 않는다) 256MiB 초과 파일은 읽지 않고 `oversize`로 둔다
- 정상 B 스캔은 다시 돌리면 `yara-B.csv`를 처음부터 다시 만든다
- 결과는 `scp`로 `yara-M-*.csv`만 가져와 그대로 `yara-report --yara`에 넘긴다(병합 단계 없음)
- 악성 zip은 맥북·운영·레포로 옮기지 않는다. `scp`는 `yara-M-*.csv`만, `find infra/quality/out -name '*.zip'`가 비어 있어야 한다
- 측정이 끝나면 VM의 `samples/`를 지우고 포털에서 VM과 디스크를 삭제한다(사용자 확인 후)

## 8. 결과 문서

`analyze`의 보고서에 측정 일시, 운영 버전(`git log -1`), corpus 크기, 데이터 출처와 한계
("합성 변종, 메타데이터 기반, 실사용 트래픽 아님")를 붙여 설계 레포 `docs/advancement/`에 둔다.
