# MalwareBazaar 블랙리스트 일괄 가져오기

공개 위협 데이터 [MalwareBazaar](https://bazaar.abuse.ch/export/)의 악성 해시와 TLSH를 블랙리스트와 TLSH 기준 지문에 넣는다.
샘플 파일은 받지 않는다. 해시와 메타데이터만 쓴다.

## 구조
```
[스크립트] --① full.csv.zip 1회 다운로드--> abuse.ch (Auth-Key)
    | ② 로컬에서 CSV를 스트리밍으로 읽기
    '--③ 1,000건씩 POST /api/admin/blacklist/import--> api-server --> DB
```
- abuse.ch에는 요청을 한 번만 보낸다. 묶음 요청은 전부 우리 서버로 간다.
- 운영은 EC2에서 스크립트를 실행하고 `--api http://localhost:8080`으로 같은 머신의 서버에 보낸다.

## 준비
1. https://auth.abuse.ch/ 에서 Auth-Key를 무료로 발급하고, **이메일 인증까지** 마친다. 인증 전에는 CSV 내보내기가 `403 Email address not confirmed`를 돌려준다.
2. 키를 파일로 저장한다(화면과 기록에 남기지 않는다).
   ```
   mkdir -p ~/.config/abusech && read -s K && printf %s "$K" > ~/.config/abusech/auth-key && chmod 600 ~/.config/abusech/auth-key
   ```
   환경 변수 `ABUSECH_AUTH_KEY`도 된다. 키는 커밋하거나 공유하지 않는다.
3. 117만 건을 넣는다면 블룸 기대 개수를 맞춘다: `LOOKUP_BLOOMFILTER_EXPECTEDHASHCOUNT=1500000` (기본 100,000).

## 실행
```
python3 infra/scripts/import_malwarebazaar.py --dry-run          # 최근 48시간, 건수만
python3 infra/scripts/import_malwarebazaar.py                    # 최근 48시간 넣기
python3 infra/scripts/import_malwarebazaar.py --full             # 전체 덤프(약 117만 건)
python3 infra/scripts/import_malwarebazaar.py --csv full.csv.zip # 미리 받은 파일
```
- 관리자 토큰: 인증을 켠 서버는 `--token` 또는 `ADMIN_TOKEN`. 로컬 개발(`AUTH_ENABLED=false`)은 필요 없다.
- localhost가 아니면 `--allow-remote`가 있어야 보낸다. 넣는 즉시 실제 판정이 바뀌기 때문이다.
- 다시 실행해도 안전하다. 이미 넣은 해시는 서버가 건너뛴다(`skippedActive`).
- Python 3.9 이상, 표준 라이브러리만 쓴다. 테스트: `python3 -m unittest infra/scripts/test_import_malwarebazaar.py`

## 서버 규칙 (`POST /api/admin/blacklist/import`, ADMIN)
- 묶음 1~1,000건이고, 묶음 하나가 한 트랜잭션이다.
  - 117만 건을 한 트랜잭션에 넣으면 행 트리거(V10)의 advisory 잠금이 Postgres 잠금 표를 넘는다.
- 블랙리스트는 `source=IMPORT`, `severity=HIGH`, `reason="MalwareBazaar: <signature>"`로 들어간다.
- 건너뛰는 것
  - 이미 활성인 해시
  - **관리자가 해제한 해시**: 오탐으로 해제한 것을 되살리지 않는다.
  - 유효한 화이트리스트 해시
- TLSH 지문은 활성 블랙리스트 해시에만 `ADMIN_IMPORT`로 넣는다. 서버가 검증한 업로드 지문은 덮어쓰지 않는다.
- 감사 로그는 묶음당 1건(`IMPORT_BLACKLIST`, 건수 요약)이다. 블룸은 커밋 뒤 한 번에 추가한다.

## 전체 덤프 규모 (2026-11-16)
| 항목 | 값 |
|---|---|
| 해시 | 1,173,953건 |
| TLSH 있음 | 808,860건 (68.9%) |
| 압축 / 풀었을 때 | 229MB / 538MB |
| 로컬에서 읽기만 할 때 | 27초 |

**주의:** 지문 81만 건이 들어가면, 지금 코드는 CheckHash에서 TLSH 비교를 할 때마다 corpus 전체를 선형으로 비교한다. 메모리 캐시 상한은 8,192건이다. 운영에 넣기 전에 측정 계획(project `docs/advancement/`)의 리허설 결과를 확인한다.
