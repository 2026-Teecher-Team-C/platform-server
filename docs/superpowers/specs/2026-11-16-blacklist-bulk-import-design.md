# 블랙리스트 일괄 가져오기 (MalwareBazaar 전체 덤프)

## 왜
측정 0단계에서 실제 규모의 블랙리스트와 TLSH 지문을 넣어야 한다. MalwareBazaar 전체 덤프(`full.csv.zip`, 2026-11-16 기준)는 해시 1,173,953건이고, 그중 808,860건(68.9%)에 TLSH가 있다. 지금 관리 API는 한 건씩만 받아서 감사 로그도 117만 건이 쌓이고, 현실적으로 넣을 수 없다.
- 측정 계획: `project/docs/advancement/2026-11-16-realistic-test-data.md`

## 방식 (사용자 선택: A)
스크립트가 abuse.ch에서 덤프를 **한 번** 받아 1,000건씩 나눠 우리 서버의 묶음 API로 보낸다. 서버는 묶음 하나를 한 트랜잭션에서 넣는다. 운영에서는 EC2에서 스크립트를 돌려 같은 머신의 API(localhost)로 보낸다.

고르지 않은 방법:
- 서버가 abuse.ch에서 직접 받는 방법: 서버에 외부 비밀값과 오래 도는 작업 관리가 필요하다.
- CSV 통째 업로드: 서버 안에서 어차피 나눠야 하고, 업로드 상한과 메모리가 커진다.

한 트랜잭션에 전부 넣을 수는 없다. 블랙리스트·지문 테이블의 행 트리거(V10)가 행마다 advisory 잠금을 잡아서, Postgres 잠금 표 한도(트랜잭션당 64 × 접속 수)를 넘는다.

## API
`POST /api/admin/blacklist/import` (ADMIN만. 기존 `anyRequest().hasRole(ADMIN)` 규칙을 그대로 쓴다)

```json
요청 { "source": "MalwareBazaar", "entries": [ { "sha256": "...", "tlsh": "T1...", "label": "Mirai" } ] }
응답 { "received": 1000, "added": 980, "skippedActive": 15, "skippedInactive": 2, "skippedWhitelisted": 1,
       "invalid": 2, "fingerprintsAdded": 700, "fingerprintsSkipped": 3 }
```

- `entries`는 1~1,000건. 넘으면 400.
- `sha256`: 소문자 hex 64자. 대문자는 정규화한다. 형식이 틀린 항목은 `invalid`로 세고 건너뛴다(묶음 전체를 실패시키지 않는다).
- `tlsh`: 선택. `^T1[0-9A-F]{70}$`일 때만 지문으로 넣는다. 형식이 틀리면 지문만 건너뛴다(`fingerprintsSkipped`).
- `label`: 선택, 최대 200자. 블랙리스트 `reason` = `"{source}: {label}"`. label이 없거나 `n/a`면 `"{source}"`.
- `source`: 필수, 최대 64자.

## 처리 규칙 (한 묶음 = 한 트랜잭션)
1. 묶음 안의 SHA를 정렬해 eligibility advisory 잠금을 순서대로 잡고, 그다음 corpus 잠금을 잡는다. 한 건짜리 쓰기와 같은 순서(eligibility → corpus)라서 교착이 생기지 않고, 묶음끼리도 정렬 순서가 같다.
2. 블랙리스트에 이미 **활성**인 SHA → `skippedActive`.
3. 블랙리스트에 **해제된**(비활성) SHA → `skippedInactive`. 관리자가 오탐으로 해제한 것을 가져오기가 되살리지 않는다.
4. 유효한 화이트리스트에 있는 SHA → `skippedWhitelisted`.
5. 나머지는 `source=IMPORT`, `severity=HIGH`, `added_by=요청 관리자`로 INSERT한다(`added`).
6. TLSH 지문은 **이 묶음 처리 뒤 활성 블랙리스트인 SHA**(새로 넣은 것과 원래 활성이던 것)에만 넣는다. `provenance=ADMIN_IMPORT`, `provenance_detail=source`. 서버가 검증한 업로드 지문(`VERIFIED_UPLOAD`, CALCULATED)은 덮어쓰지 않는다(기존 `saveImported` 규칙).
7. 감사 로그는 묶음당 1건. action `IMPORT_BLACKLIST`, target `BLACKLIST`, detail은 source와 건수.
8. 커밋 뒤 새로 넣은 SHA 전부를 블룸에 한 번에 추가한다(`BloomFilterPort.addAll`, Redis 파이프라인 한 번).

## 바꾸는 곳
- hashlist: `ImportBlacklistBatchUseCase`, JDBC 일괄 쓰기 포트(JPA 엔티티 1,000개 저장 대신), 컨트롤러 엔드포인트와 요청·응답 DTO
- verdict: 지문 일괄 저장(`ImportFileFingerprints.importBatch`, 행별 감사 없음, MANDATORY 트랜잭션)
- lookup: `BloomFilterPort.addAll(Collection<String>)` + Redis 파이프라인 구현, `BloomAfterCommit.addAll`
- audit: `AuditAction.IMPORT_BLACKLIST`
- 스크립트: `infra/scripts/import_malwarebazaar.py`
  - 10월에 만든 버전(커밋 안 됨, 로컬 백업)을 바탕으로 한다.
  - Auth-Key는 `--auth-key-file`(기본 `~/.config/abusech/auth-key`)이나 환경변수 `ABUSECH_AUTH_KEY`에서 읽는다.
  - `--full`이면 전체 덤프, 아니면 최근 48시간. `--csv`로 미리 받은 파일을 쓸 수도 있다.
  - 1,000건씩 묶음 API로 보내고, 진행률과 누적 건수를 출력한다.
  - localhost가 아니면 `--allow-remote`를 요구한다(기존 안전장치).
  - 표준 라이브러리만 쓴다.

## 비목표
- TLSH 비교 성능(지문 81만 건이면 CheckHash마다 전체 선형 비교). 측정 뒤 고도화로 다룬다.
- 블룸 기대 개수는 설정값이라 운영 환경변수로 맞춘다(120만 이상).
- 정기 자동 갱신.

## 빌더에게 맡기는 것
- SQL 세부(배치 INSERT, `ON CONFLICT DO NOTHING`), 클래스 이름, 테스트 위치.
- 아래 항목을 테스트로 확인한다.
  - 새로 넣기, 이미 활성 / 해제됨 / 화이트리스트 건너뛰기
  - 잘못된 SHA와 TLSH 처리
  - 1,000건 초과 400
  - VERIFIED 지문은 덮어쓰지 않음
  - 감사 로그 1건, 블룸 반영
  - 같은 묶음을 두 번 보내도 결과가 같음(멱등)
  - 한 건짜리 등록과 동시에 실행해도 교착 없음
