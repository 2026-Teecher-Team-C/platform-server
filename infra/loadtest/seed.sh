#!/usr/bin/env bash
# 운영과 같은 악성 지문 시드 파일(gzip CSV: sha256,tlsh,reason,severity)을 로컬 부하 시험 DB에 넣는다.
# make loadtest-seed FILE=<경로> 가 부른다(레포 루트에서). run.sh도 시드가 부족하고 파일이 있으면 이 스크립트를 부른다.
#
# 적재: 관리 API(1,000건 묶음, 117만 건이면 약 20분)가 아니라 COPY로 임시 테이블에 넣고 INSERT ... SELECT 한 번.
# 반영: api-server를 재기동한다. 기동 직후 블룸 필터 1차 재구축(BloomFilterRebuildService, initialDelay=0)이
#       hash_blacklist를 포함해 다시 만들고, 메모리 TLSH corpus(InMemoryTlshCorpus.warmUp)가 새 토큰으로 전부 읽는다.
#
# 환경 변수: FILE(없으면 기본 위치), SEED_SHA256(파일 검증값), CPU_SPLIT
set -euo pipefail

cd "$(dirname "$0")/../.."
. infra/loadtest/lib.sh

SEED_SHA256=${SEED_SHA256:-$DEFAULT_SEED_SHA256}
FILE=${FILE:-}
ALGORITHM_VERSION=TLSH-128-1-T1-force50-java-ebdec8fde93a4ac359437f4f3796c78d3ae433bf  # FingerprintCalculation.ALGORITHM_VERSION

T_ALL=$SECONDS
say "시드 파일 확인"
if [ -z "$FILE" ]; then
    FILE=$(find_seed_file) || die "시드 파일이 없다. 팀 공유 드라이브에서 malicious-fingerprints-*.csv.gz를 받아
  레포 루트의 loadtest-seed/ (또는 레포 옆 ../loadtest-seed/)에 두거나 FILE=<경로>로 지정한다.
  받는 방법은 infra/loadtest/README.md의 '운영과 같은 지문 시드' 절을 본다."
fi
[ -f "$FILE" ] || die "파일이 없다: $FILE"
ACTUAL=$(sha256_of "$FILE")
echo "파일: $FILE"
echo "sha256: $ACTUAL"
if [ "$ACTUAL" != "$SEED_SHA256" ]; then
    die "sha256이 다르다(기대 ${SEED_SHA256}). 파일이 손상됐거나 다른 버전이다. 다른 버전을 일부러 쓰려면 SEED_SHA256=<값>을 준다."
fi

compose_files
precheck
start_stack

T=$SECONDS
say "임시 테이블에 COPY"
psql_q -c "DROP TABLE IF EXISTS loadtest_seed_staging" \
       -c "CREATE UNLOGGED TABLE loadtest_seed_staging (sha256 text, tlsh text, reason text, severity text)"
gzip -dc "$FILE" | psql_q -c "\\copy loadtest_seed_staging FROM pstdin WITH (FORMAT csv, HEADER true)"
echo "COPY 완료: $(psql_q -c 'SELECT count(*) FROM loadtest_seed_staging')행 ($((SECONDS - T))초)"

ADMIN_USERNAME=$(sed -n 's/^ADMIN_BOOTSTRAP_USERNAME=//p' infra/dev.env)
ADMIN_ID=$(psql_q -c "SELECT admin_id FROM admin_users WHERE username = '${ADMIN_USERNAME}'" | tr -d '[:space:]')
[ -n "$ADMIN_ID" ] || die "초기 관리자(${ADMIN_USERNAME})가 DB에 없다. api-server 기동 로그를 확인한다."

T=$SECONDS
say "hash_blacklist·file_fingerprints에 넣기"
psql_q -v admin="$ADMIN_ID" -v algo="$ALGORITHM_VERSION" -f - <<'SQL'
BEGIN;
-- V10 행 트리거(similarity_*_lock / *_revision)는 행마다 advisory 잠금을 잡는다. 117만 행을 한 트랜잭션에 넣으면
-- 잠금 표를 넘고, 행마다 corpus revision UPDATE가 돈다. 이 트랜잭션에서만 트리거를 끄고(session_replication_role,
-- 로컬 DB 소유자 = 슈퍼유저), 트리거가 하던 일(similarity_cache 비우기, corpus revision 올리기)은 아래에서 한 번에 한다.
-- FK 검사도 트리거라 같이 꺼진다 — imported_by는 위에서 실제 관리자 id를 확인해 넣는다. CHECK 제약은 그대로 검사된다.
SET LOCAL session_replication_role = replica;

CREATE TEMP TABLE seed_valid ON COMMIT DROP AS
SELECT DISTINCT ON (lower(sha256))
       lower(sha256) AS sha256,
       CASE WHEN upper(tlsh) ~ '^T1[0-9A-F]{70}$' THEN upper(tlsh) END AS tlsh,
       COALESCE(NULLIF(reason, ''), 'MalwareBazaar') AS reason,
       CASE WHEN upper(severity) IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL') THEN upper(severity) ELSE 'HIGH' END AS severity
FROM loadtest_seed_staging
WHERE lower(sha256) ~ '^[0-9a-f]{64}$';

SELECT 'valid_rows', count(*), count(tlsh) FROM seed_valid;

-- 관리 API 일괄 가져오기와 같은 규칙: 이미 있는 해시(활성·해제 모두)와 유효한 화이트리스트 해시는 건너뛴다.
WITH ins AS (
    INSERT INTO hash_blacklist (hash_type, hash_value, reason, severity, source, added_by, is_active, created_at)
    SELECT 'SHA256', s.sha256, s.reason, s.severity, 'IMPORT', NULL, true, now()
    FROM seed_valid s
    WHERE NOT EXISTS (SELECT 1 FROM hash_whitelist w
                      WHERE w.hash_type = 'SHA256' AND w.hash_value = s.sha256 AND w.is_active
                        AND (w.expires_at IS NULL OR w.expires_at > now()))
    ON CONFLICT DO NOTHING
    RETURNING 1)
SELECT 'blacklist_added', count(*) FROM ins;

-- 지문은 활성 블랙리스트 해시에만 ADMIN_IMPORT로. 이미 있는 지문(서버가 검증한 업로드 포함)은 덮어쓰지 않는다.
WITH ins AS (
    INSERT INTO file_fingerprints (sha256, tlsh, calculation_status, algorithm_version, calculated_at,
                                   provenance, provenance_detail, imported_by)
    SELECT s.sha256, s.tlsh, 'CALCULATED', :'algo', now(), 'ADMIN_IMPORT', 'MalwareBazaar', :'admin'::uuid
    FROM seed_valid s
    JOIN hash_blacklist b ON b.hash_type = 'SHA256' AND b.hash_value = s.sha256 AND b.is_active
    WHERE s.tlsh IS NOT NULL
    ON CONFLICT DO NOTHING
    RETURNING 1)
SELECT 'fingerprints_added', count(*) FROM ins;

-- 꺼 둔 트리거 몫: 새 지문의 유사도 캐시를 지우고 corpus 토큰을 바꾼다(메모리 corpus·캐시가 옛 목록으로 답하지 않게).
DELETE FROM similarity_cache c USING seed_valid s WHERE c.sha256 = s.sha256;
UPDATE similarity_corpus_state SET revision = revision + 1, generation = gen_random_uuid();
COMMIT;

DROP TABLE loadtest_seed_staging;
ANALYZE hash_blacklist;
ANALYZE file_fingerprints;
SQL
echo "DB 적재 $((SECONDS - T))초"
EXPECTED=$(active_fingerprints)
echo "활성 악성 지문(active_malicious_fingerprints): ${EXPECTED}건"

T=$SECONDS
say "api-server 재기동(블룸 필터 재구축 + 메모리 TLSH corpus 재적재)"
"${COMPOSE[@]}" restart api-server
"${CLIENT[@]}" loadtest-client wait --timeout 600

say "반영 확인"
SAMPLE=$(psql_q -F ' ' -c "SELECT f.sha256, f.tlsh FROM file_fingerprints f
    WHERE f.provenance_detail = 'MalwareBazaar' AND f.calculation_status = 'CALCULATED' ORDER BY f.sha256 LIMIT 1")
set -- $SAMPLE
"${CLIENT[@]}" loadtest-client verify-seed --sha "$1" --tlsh "$2" --expect-corpus "$EXPECTED"
echo "재기동·반영 $((SECONDS - T))초"
echo
echo "전체 $((SECONDS - T_ALL))초"
