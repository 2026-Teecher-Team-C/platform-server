# sha256 CHAR(64) → VARCHAR(64) 전환 설계

## 왜

`sha256` 컬럼은 `CHAR(64)`, `hash_blacklist`/`hash_whitelist.hash_value`는 `VARCHAR(128)`(SHA256·TLSH 공용)이다.
둘을 비교하면 PostgreSQL이 varchar 쪽을 `bpchar`로 바꿔 그쪽 인덱스를 못 쓴다. MalwareBazaar 적재(117만 행) 뒤
트리거(V15에서 수정)와 대시보드 뷰(V17, PR #103에서 수정)가 차례로 수 초~수십 초 걸렸다. 조인마다 캐스트를
붙이는 대신 타입을 맞춰 같은 문제가 다시 생기지 않게 한다.

## 목표 / 비목표

- 목표: public 스키마의 `character(n)` 컬럼을 전부 `VARCHAR(n)`으로. 이후 sha256 ↔ hash_value 비교가 캐스트 없이 인덱스를 쓴다.
- 비목표: `hash_value`를 SHA256/TLSH 열로 나누기, 컬럼 길이·제약 변경, 쿼리 튜닝.
- 반목표: 판정 결과나 데이터가 바뀌는 것. 마이그레이션 뒤 값·행 수·FK·CHECK는 그대로여야 한다.

## 제약

- 기존 마이그레이션 파일은 수정하지 않는다(새 `V18__...`). `ddl-auto: validate`라 엔티티 매핑도 같이 바꾼다.
- `ALTER COLUMN TYPE`은 테이블을 다시 쓰고 ACCESS EXCLUSIVE 잠금을 건다. `file_fingerprints`(117만 행, 493MB)가
  잠긴 동안 CheckHash가 멈추고 fail-close라 다운로드가 차단된다. 사용자 없음 → 배포 중 잠깐 차단을 받아들인다(2026-11-17 결정).

## 대상 (운영 DB 2026-11-17 기준)

| 테이블 | 컬럼 | 행 수 |
|---|---|---|
| file_fingerprints | sha256 (PK) | 1,173,551 |
| download_events | sha256 | 234 |
| analyses | sha256 | 59 |
| file_verdicts | sha256 (PK) | 57 |
| event_decision_evidence | sha256 | 14 |
| similarity_assessments | sha256, matched_sample_sha256 | 4 |
| similarity_cache | sha256 (PK) | 4 |
| pending_quarantine_uploads | sha256 | 0 |
| quarantine_files | sha256 | 0 |

의존 객체: 뷰 `download_event_threats`, `active_malicious_fingerprints`, `current_similarity_corpus_token`,
함수 `revise_similarity_corpus`(V15의 `::bpchar` 캐스트), 엔티티 `DownloadEvent`·`QuarantineFile`·`Analysis`·`FileVerdict`의
`@JdbcTypeCode(SqlTypes.CHAR)`.

## 접근

| 방법 | 장단점 | 결정 |
|---|---|---|
| 마이그레이션 하나로 전부 전환 | 단순. 배포 중 잠금 시간만큼 다운로드 차단 | **채택** |
| `TEXT` + CHECK | VARCHAR와 이득 차이 없음, 요청과 다름 | 제외 |
| 새 컬럼 추가·백필·교체의 무중단 전환 | 차단 없음. 단계·코드가 크게 늘어남. 사용자가 없어 필요 없음 | 제외 |

## 설계

V18 한 파일, Flyway 트랜잭션 하나:
1. 뷰 3개 DROP.
2. 컬럼 10개 `ALTER COLUMN ... TYPE VARCHAR(64)`. PK·FK·CHECK·인덱스는 PostgreSQL이 유지·재생성한다.
3. 뷰 3개를 현재 정의 그대로 다시 만든다. `download_event_threats`의 `::text` 캐스트(V17)는 뺀다.
4. `revise_similarity_corpus`를 `::bpchar` 없이 다시 만든다.
5. 엔티티 4개의 `@JdbcTypeCode(SqlTypes.CHAR)` 제거. 위젯 SQL의 `::text`(PR #103)는 없어도 되지만 남겨도 해가 없어 그대로 둔다.

## 검증

- public 스키마에 `character` 타입 컬럼이 없다는 테스트.
- PR #103의 인덱스 사용 테스트 유지, `DevelopMigrationUpgradeTest`로 기존 이력 위 업그레이드 확인.
- 로컬에 117만 행을 넣고 V18 소요 시간을 재서 운영 차단 시간을 미리 알린다.

## 빌더에게 남긴 것

- 뷰·함수의 "현재 정의"는 마지막으로 그 객체를 만든 마이그레이션 파일을 기준으로 한다.
- 브랜치는 PR #103 위에 쌓고, #103이 머지된 뒤 develop 대상으로 PR을 연다.
