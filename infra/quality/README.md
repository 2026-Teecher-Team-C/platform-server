# 탐지 품질 측정 하네스

운영 서버 `CheckHash`에 신종 악성·정상·변종 파일의 (sha256, tlsh)를 보내고, 서버가 남긴 TLSH 근거로
탐지율·오탐률·변종 탐지율과 임계값 곡선을 계산한다. 설계: 설계 레포
`docs/superpowers/specs/2026-11-25-detection-quality-measurement-design.md`.

- 악성 파일은 내려받지 않는다. 악성 쪽은 MalwareBazaar 메타데이터(SHA-256, TLSH, 패밀리)만 쓴다
- 측정 데이터와 결과는 `infra/quality/out/`에만 둔다. **커밋하지 않는다**(공개 레포)
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
make quality ARGS="build-variants"
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

```bash
make quality ARGS="policy set PROD --block <제안 차단> --warn <제안 경고>"
make quality ARGS="run PROD PRODG --items out/items-M.csv out/items-B.csv --pass-id p2"
bash infra/quality/prod_sql.sh infra/quality/sql/export_evidence.sql agent_id=$(cat infra/quality/out/agent-p2.txt) \
    > infra/quality/out/evidence-p2.csv
make quality ARGS="predict --items out/items-M.csv out/items-B.csv --base-responses out/responses-p1.csv \
    --base-evidence out/evidence-p1.csv --responses out/responses-p2.csv --block <제안 차단>"   # 불일치 0건이어야 함
make quality ARGS="policy set PROD --block 30 --warn 70"           # 되돌린 뒤 policy get으로 확인
```

## 5. 변종 — 실행 전 확인 받기

```bash
make quality ARGS="variants register PROD --run-id r1"             # 원본만 등록(사유 quality-test-r1)
make quality ARGS="run PROD PRODG --items out/items-V.csv --pass-id v1"
make quality ARGS="variants release PROD --run-id r1"              # 실패 0이어야 종료 코드 0
bash infra/quality/prod_sql.sh infra/quality/sql/export_evidence.sql agent_id=$(cat infra/quality/out/agent-v1.txt) \
    > infra/quality/out/evidence-v1.csv
```

## 6. 정리 — 실행 전 확인 받기

근거를 모두 CSV로 뽑은 뒤에 한다. 측정 에이전트(p1, p2, v1)마다:

```bash
bash infra/quality/prod_sql.sh infra/quality/sql/cleanup_preview.sql agent_id=<agent_id>   # 지울 건수 확인
bash infra/quality/prod_sql.sh infra/quality/sql/cleanup.sql agent_id=<agent_id>           # 삭제 + REVOKED
```

마지막으로 `policy get`이 30/70인지, 변종 원본이 모두 해제됐는지 확인한다. 서버는 사유를 `quality-test: quality-test-r1`
형태(가져오기 source + label)로 저장하므로 `SELECT count(*) FROM hash_blacklist WHERE is_active AND reason LIKE '%quality-test-r1'`이
0이어야 하고, 같은 조건에서 `is_active`를 빼면 등록 건수와 같아야 한다. 감사 로그는 남긴다.

## 7. 결과 문서

`analyze`의 보고서에 측정 일시, 운영 버전(`git log -1`), corpus 크기, 데이터 출처와 한계
("합성 변종, 메타데이터 기반, 실사용 트래픽 아님")를 붙여 설계 레포 `docs/advancement/`에 둔다.
