# YARA 룰·해시 데이터 넣기 가이드

탐지에 쓸 YARA 룰과 해시 목록(블랙리스트·화이트리스트)을 우리 DB에 넣는 방법이다. 개발 환경(`make dev`) 기준이며,
2026-11-11 develop 코드로 확인했다.

## 0. 먼저 알아 둘 것

| 데이터 | 저장 위치 | 반영 시점 | 넣는 방법 |
|---|---|---|---|
| YARA 룰 | `yara_rulesets`(원문) + `yara_rules`(룰 이름·심각도) | 버전을 **활성화**한 뒤, 다음 검사부터 | 관리 API로 `.yar` 업로드 → 활성화 (1장) |
| 블랙리스트 해시 | `hash_blacklist` | **즉시**. 다운로드마다 DB를 직접 조회한다 | 소량은 API, 대량은 SQL (2장) |
| 화이트리스트 해시 | `hash_whitelist` | **즉시** | API (3장) |

- **YARA 룰은 SQL로 넣지 않는다.** 룰 원문을 탐지 엔진이 컴파일해 봐야 하고, 활성화할 때 엔진에 적재하는 일까지 API 서버가 한다. 테이블에 직접 넣으면 엔진은 그 룰을 모른다.
- **조회 순서는 화이트리스트 → 블랙리스트 → 판정 이력(블룸 필터 + `file_verdicts`) → 엔진 검사다.** 같은 해시가 양쪽에 있으면 화이트리스트가 이긴다.
- **관리자 쓰기 작업은 감사 로그를 남기는 것이 팀 규칙이다**(`CLAUDE.md`). API로 넣으면 자동으로 남고, SQL로 넣으면 남지 않는다. SQL 일괄 등록은 2-2의 조건에서만 쓴다.

### 공통 준비: 관리자 토큰 받기

관리 API는 Bearer 토큰이 필요하다. 개발 환경의 API 서버는 `http://localhost:8080`이다.

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/admin/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"<관리자 계정>","password":"<비밀번호>"}' | jq -r .accessToken)
```

쓰기 API는 모두 **ADMIN 역할**만 쓸 수 있다.

---

## 1. YARA 룰 넣기

### 1-1. 룰셋은 "버전" 단위로 통째로 바뀐다

- 업로드 한 번 = `.yar` 파일 하나 = 새 룰셋 버전 하나다. 버전 번호는 서버가 1, 2, 3…으로 붙인다.
- 활성 버전은 하나뿐이다. 새 버전을 활성화하면 **이전 버전의 룰은 더 이상 쓰이지 않는다.**
- 그래서 룰을 **추가**하려면 지금 쓰는 룰 + 새 룰을 한 파일로 합쳐서 새 버전으로 올린다.
- 지금 활성 버전의 원문은 콘솔 **YARA 룰셋** 화면이나 `GET /api/admin/rulesets`(버전 목록), `GET /api/admin/rulesets/{version}/rules`(룰 목록)로 확인한다.

### 1-2. 파일 조건 (어기면 업로드가 거부된다)

| 조건 | 거부될 때 |
|---|---|
| 확장자 `.yar` | 400 |
| UTF-8 텍스트, 1 MiB 이하, 비어 있지 않음 | 400 |
| 탐지 엔진에서 컴파일 성공, 룰 1개 이상 | 422 (엔진의 컴파일 오류 메시지가 그대로 온다) |
| 탐지 엔진에 연결 가능 | 503 |

컴파일에서 자주 막히는 것:

- **`include` 지시문은 못 쓴다.** 엔진이 서버 파일을 끌어오지 못하게 막아 두었다. 여러 파일은 내용을 한 파일로 합친다.
- **외부 변수(`filename`, `filepath`, `extension` 등)를 쓰는 룰은 컴파일되지 않는다.** 엔진이 외부 변수를 정의하지 않는다. 공개 룰 모음(예: signature-base)에 이런 룰이 섞여 있으니 빼고 올린다.
- 룰 이름이 겹치면 컴파일 오류다. 여러 출처를 합칠 때 확인한다.
- 큰 공개 룰 모음은 1 MiB를 넘는다. 필요한 룰만 골라 올린다.

### 1-3. 심각도는 `meta: severity`로 정한다

```yara
rule Example_Dropper
{
    meta:
        severity = "HIGH"          // LOW | MEDIUM | HIGH | CRITICAL (대소문자 무관)
        description = "설명"
    strings:
        $a = "..."
    condition:
        $a
}
```

- 없거나 허용 값이 아니면 **MEDIUM**이 된다. 공개 룰은 `severity`가 없거나 숫자(`score = 70`)인 경우가 많으니 올리기 전에 채운다.
- 심각도는 대시보드의 "확인 안 한 높은 심각도 차단"(HIGH 이상), 위협별 현황, 이벤트 심각도에 그대로 쓰인다.

### 1-4. 올리고 활성화하기

```bash
# 1) 업로드 → 대기 버전이 생긴다 (201, 응답의 version 확인)
curl -s -X POST http://localhost:8080/api/admin/rulesets \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@merged.yar"

# 2) 활성화 → 엔진에 적재된다
curl -s -X POST http://localhost:8080/api/admin/rulesets/<version>/activate \
  -H "Authorization: Bearer $TOKEN"
```

콘솔 **YARA 룰셋** 화면에서도 같은 일을 할 수 있다.

활성화하면:

- 지금까지 엔진이 내린 판정이 **stale**로 바뀐다. 이미 검사한 파일도 다음 다운로드 때 새 룰로 다시 검사한다.
- 감사 로그에 `UPLOAD_RULESET`, `ACTIVATE_RULESET`이 남는다.
- 특정 룰만 끄고 싶으면 `PATCH /api/admin/rulesets/{version}/rules/{ruleId}`에 `{"enabled": false}`를 보낸다. 새 버전을 올릴 필요가 없다.

### 1-5. 확인

- 콘솔 **YARA 룰셋** 화면에서 새 버전이 "활성"인지, 룰 수가 맞는지 본다.
- 룰에 맞는 무해한 테스트 파일로 다운로드를 시도해 차단되는지 본다. 팀 데모 룰(`detection-engine/rules/teecher_demo.yar`)과 데모 샘플이 예시다. **실제 악성코드 샘플로 시험하지 않는다.**

> `detection-engine/rules/*.yar`은 엔진이 처음 뜰 때 쓰는 기본 룰이다. API 서버가 활성 버전을 적재하면 그 버전으로 바뀐다.
> 운영 룰은 이 폴더가 아니라 1-4의 업로드로 관리한다.

---

## 2. 블랙리스트 해시 넣기

### 2-1. 소량: API로 한 건씩

```bash
curl -s -X POST http://localhost:8080/api/admin/blacklist \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"sha256":"<64자리 SHA-256>","reason":"출처·사유"}'
```

- 심각도는 **HIGH**, 출처는 `MANUAL`로 저장된다. 지금 API에는 심각도를 바꾸는 값이 없다.
- 409: 이미 블랙리스트에 있거나 **화이트리스트에 있는 해시**다.
- 해제했던 해시를 다시 등록하면 되살아난다.
- 감사 로그 `ADD_BLACKLIST`가 남는다.

수십~수백 건이면 같은 호출을 반복하는 스크립트로 충분하다. 감사 로그도 건마다 남는다.

```bash
# hashes.txt: 한 줄에 "sha256<TAB>사유"
while IFS=$'\t' read -r sha reason; do
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:8080/api/admin/blacklist \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d "$(jq -n --arg s "$sha" --arg r "$reason" '{sha256:$s, reason:$r}')")
  echo "$code $sha"
done < hashes.txt
```

### 2-2. 대량: SQL 일괄 등록

수천 건 이상(예: 공개 악성 해시 피드)은 SQL로 넣는다. **조건:** 팀에 공유하고 넣으며, 넣은 파일과 건수를 PR이나 이슈에 남긴다(감사 로그 대신).

psql은 postgres 컨테이너 안에서 돌기 때문에 CSV도 컨테이너 안으로 먼저 복사한다(레포 루트에서 실행).

```bash
# bash·zsh 모두에서 쓰도록 함수로 둔다(zsh는 변수에 담은 명령을 띄어쓰기로 나누지 않는다)
dc() { docker compose --env-file infra/dev.env -f infra/compose.yml -f infra/compose.dev.yml "$@"; }
dc cp hashes.csv postgres:/tmp/hashes.csv
dc exec postgres psql -U platform -d platform
```

```sql
-- 0) 실수하면 되돌릴 수 있게 트랜잭션 안에서 한다. 결과를 보고 COMMIT 또는 ROLLBACK.
BEGIN;

-- 1) 임시 테이블에 CSV를 올린다 (sha256,reason 두 열, 헤더 없음)
CREATE TEMP TABLE import_hashes (sha256 TEXT, reason TEXT);
\copy import_hashes FROM '/tmp/hashes.csv' WITH (FORMAT csv)

-- 2) 형식이 맞는 것만, 화이트리스트에 없는 것만, 이미 있으면 건너뛰고 넣는다
INSERT INTO hash_blacklist (hash_type, hash_value, reason, severity, source, added_by)
SELECT 'SHA256', lower(trim(sha256)), coalesce(nullif(trim(reason), ''), 'bulk import'), 'HIGH', 'IMPORT', NULL
FROM import_hashes
WHERE lower(trim(sha256)) ~ '^[0-9a-f]{64}$'
  AND NOT EXISTS (
      SELECT 1 FROM hash_whitelist w
      WHERE w.hash_type = 'SHA256' AND w.hash_value = lower(trim(import_hashes.sha256)) AND w.is_active)
ON CONFLICT (hash_type, hash_value) DO NOTHING;
-- "INSERT 0 N"의 N이 새로 들어간 건수다. 형식이 틀린 줄·화이트리스트 해시·이미 있는 해시는 빠진다.

-- 3) 확인 후 확정
SELECT count(*) FROM hash_blacklist WHERE source = 'IMPORT' AND created_at >= now() - interval '10 minutes';
COMMIT;   -- 이상하면 ROLLBACK;
```

끝나면 `dc exec postgres rm /tmp/hashes.csv`로 복사한 파일을 지운다.

지켜야 할 것:

- **해시는 소문자 64자리로 넣는다.** 조회가 소문자 그대로 비교하므로 대문자로 넣으면 걸리지 않는다.
- `source`는 `'IMPORT'`, `added_by`는 `NULL`이다(사람이 넣은 게 아니라는 표시). 콘솔 목록에서 출처로 걸러 볼 수 있다.
- `hash_type`은 `'SHA256'`만 쓴다. `TLSH`는 자리만 있고 아무 단계도 계산하지 않아서 탐지되지 않는다.
- 블랙리스트는 블룸 필터와 상관없이 DB를 바로 조회하므로 **넣는 즉시 차단된다.** 서버 재시작이 필요 없다.
- 기존 마이그레이션 파일에 데이터를 넣지 않는다. 모든 환경에 꼭 있어야 하는 시드라면 새 `V{n}__...sql`을 만든다.

### 2-3. 빼기

- 한 건: 콘솔 **블랙리스트** 화면 또는 `DELETE /api/admin/blacklist/{sha256}`. 행은 지우지 않고 `is_active = false`가 된다(이력 보존).
- 일괄로 넣은 것을 되돌릴 때:
  ```sql
  UPDATE hash_blacklist SET is_active = false, deactivated_at = now()
  WHERE source = 'IMPORT' AND created_at >= '<넣은 시각>';
  ```

---

## 3. 화이트리스트 해시 넣기

화이트리스트는 오탐 예외라 **API로만 넣는다**(`added_by`가 필수라 누가 예외를 걸었는지 남아야 한다).

```bash
curl -s -X POST http://localhost:8080/api/admin/whitelist \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"sha256":"<64자리 SHA-256>","reason":"사내 배포 도구","expiresAt":null}'
```

- `expiresAt`(ISO-8601)을 주면 그 시각 이후 자동으로 효력이 없어진다. 없으면 무기한이다.
- 화이트리스트에 넣으면 블랙리스트·판정 이력·엔진 검사를 모두 건너뛰고 통과한다. 사유를 구체적으로 쓴다.
- 격리 파일을 "복원"하면 자동으로 화이트리스트에 들어간다. 오탐 처리는 그쪽이 기본 경로다.

---

## 4. 외부 데이터를 가져올 때

공개 룰·해시를 쓰기 전에 **라이선스를 확인하고**, 출처와 버전(날짜·커밋)을 `reason`이나 룰 `meta`에 남긴다.

| 종류 | 예시 출처 | 확인할 점 |
|---|---|---|
| 악성 해시 피드 | abuse.ch MalwareBazaar 등 | 이용 약관, 갱신 주기. SHA-256 열만 골라 2-2로 넣는다 |
| YARA 룰 모음 | YARA-Forge, signature-base, ReversingLabs 공개 룰 등 | 라이선스(룰마다 다름), 외부 변수 사용 여부(1-2), `severity` 없음(1-3), 크기 1 MiB |

- 라이선스 조건은 출처마다 다르고 바뀔 수 있으니, 가져오는 시점에 각 저장소의 LICENSE를 직접 확인한다.
- 룰을 대량으로 넣으면 오탐도 늘어난다. 처음에는 작은 묶음으로 올리고, 대시보드의 위협별 현황·매칭 룰 Top 5에서 특정 룰이 과하게 걸리는지 본 뒤 늘린다. 과하게 걸리는 룰은 1-4의 룰 끄기로 바로 끈다.
- 실제 악성 파일은 받지도, 레포에 올리지도 않는다. 해시와 룰 텍스트만 다룬다.
