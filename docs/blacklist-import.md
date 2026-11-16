# MalwareBazaar 블랙리스트 일괄 가져오기

공개 위협 데이터 [MalwareBazaar](https://bazaar.abuse.ch/export/)의 악성 해시와 TLSH를 블랙리스트와 TLSH 기준 지문에 넣는다.
샘플 파일은 받지 않는다. 해시와 메타데이터만 쓴다.

> **운영에 `--full`을 넣기 전에 반드시 읽을 것 (2026-11-16 리허설 실측)**
> 1. **적재 후 TLSH CheckHash가 다운로드마다 약 4~6초 걸린다.** TLSH 비교 대상 1,173,728건을 매번 선형으로 비교한다(메모리 캐시 상한 8,192건). TLSH 없는 CheckHash는 약 6ms로 그대로다.
>    - TLSH 비교 인덱스(고도화)가 생기기 전에는 운영에 `--full`을 넣지 않는다. 최근 48시간(기본값)만 넣는다.
>    - 에이전트 쪽 RPC 시간 제한이 이보다 짧으면 fail-close로 다운로드가 차단된다.
> 2. **적재가 도는 동안(전체 덤프 기준 약 20분) 모든 CheckHash가 UNKNOWN이 되어 업로드·YARA 재검사로 넘어간다.**
>    - 묶음 트랜잭션이 corpus 배타 잠금을 쥐어 CheckHash의 corpus try-lock이 실패하기 때문이다.
>    - 차단이 빠지지는 않는다. 업로드 뒤 SubmitFile이 정확 목록을 다시 확인한다.
>    - 하지만 업로드량과 엔진 부하가 늘어나므로 유지보수 시간대에 실행한다.

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
| TLSH 있음 | 808,860건 `T1` 형식 + 364,868건 `T1` 없는 예전 형식(앞에 `T1`을 붙여 받는다) = 비교 대상 1,173,728건 |
| 압축 / 풀었을 때 | 229MB / 538MB |
| 로컬에서 읽기만 할 때 | 27초 |

## 리허설 결과 (로컬 운영 구성, 2026-11-16)
| 항목 | 값 |
|---|---|
| 적재 속도 | 약 1,500건/초. 이미 있는 해시 건너뛰기는 약 1,000건/초 |
| DB 크기 | 842MB (`hash_blacklist` 약 335MB, `file_fingerprints` 약 340MB 이상) |
| CheckHash, TLSH 없음 | 중앙값 5.9ms |
| CheckHash, TLSH 비교 | 중앙값 4.3~6.0초 |

- 블룸 기대 개수를 바꾸면 비트 배열 크기가 바뀐다. 재기동 뒤 첫 재구축이 끝날 때까지는 블룸이 누락을 낼 수 있으므로, 재구축 완료(기동 직후 1회) 뒤에 적재한다.
- 응답 시간 초과 뒤 재시도한 묶음은 서버가 이미 넣었으면 `skippedActive`로 센다. 그래서 최종 `added`가 실제보다 적게 보일 수 있다.
- 진행률을 파일로 남길 때는 `python3 -u`를 쓴다(출력 버퍼링).
