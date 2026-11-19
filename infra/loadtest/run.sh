#!/usr/bin/env bash
# 로컬 부하 시험 실행기. make loadtest / make loadtest-burst가 부른다(레포 루트에서). Mac·Linux·Windows(WSL) bash.
#
# 환경 변수(Makefile이 넘긴다): MODE(bench|burst) BURST SIZE CONC RATE DURATION ARRIVAL ARRIVAL_SEED MAX_INFLIGHT
#   BW COUNT QUICK WARMUP SLO SEED TIMEOUT CPU_SPLIT FILE SEED_SHA256
set -euo pipefail

cd "$(dirname "$0")/../.."
. infra/loadtest/lib.sh

MODE=${MODE:-bench}
BURST=${BURST:-300}
SIZE=${SIZE:-10}
CONC=${CONC:-}
RATE=${RATE:-}
DURATION=${DURATION:-0}
ARRIVAL=${ARRIVAL:-poisson}
ARRIVAL_SEED=${ARRIVAL_SEED:-1}
MAX_INFLIGHT=${MAX_INFLIGHT:-200}
BW=${BW:-}
COUNT=${COUNT:-0}
QUICK=${QUICK:-}
WARMUP=${WARMUP:--1}
SLO=${SLO:-10}
SEED=${SEED:-50000}
TIMEOUT=${TIMEOUT:-120}

# ---- 1. 시작 전 점검 ----------------------------------------------------------
say "시작 전 점검"
if [ "$MODE" = burst ] && { [ -n "$CONC" ] || [ -n "$RATE" ]; }; then
    die "make loadtest-burst에는 CONC·RATE를 주지 않는다(BURST=건수, SIZE=크기, BW=회선만)."
fi
if [ -n "$CONC" ] && [ -n "$RATE" ]; then
    die "CONC(닫힌 루프)와 RATE(개방형 도착)는 함께 쓸 수 없다. 하나만 준다."
fi
if [ -z "$CONC" ] && [ -z "$RATE" ]; then
    CONC=1,2,4,8
fi
case "$ARRIVAL" in poisson|uniform) ;; *) die "ARRIVAL은 poisson 또는 uniform이다: $ARRIVAL" ;; esac
compose_files
precheck

# ---- 2. 서버 기동 ---------------------------------------------------------------
start_stack

# ---- 3. 악성 지문 시드 ----------------------------------------------------------
# 운영과 같은 시드 파일이 기본 위치에 있으면 그것을 넣고(seed.sh), 없으면 무작위 지문 SEED건으로 대신한다.
say "악성 지문 시드 확인 (목표 ${SEED}건 이상)"
CURRENT=$(active_fingerprints)
echo "현재 활성 악성 지문(active_malicious_fingerprints): ${CURRENT}건"
if [ "$CURRENT" -ge "$SEED" ]; then
    echo "이미 ${SEED}건 이상 있다 — 시드를 건너뛴다."
elif SEED_FILE=$( if [ -n "${FILE:-}" ]; then echo "$FILE"; else find_seed_file; fi ); then
    echo "운영과 같은 시드 파일을 넣는다: ${SEED_FILE}"
    FILE="$SEED_FILE" bash infra/loadtest/seed.sh
    CURRENT=$(active_fingerprints)
else
    echo "시드 파일이 없어(loadtest-seed/*.csv.gz) 무작위 지문 ${SEED}건으로 대신한다. 운영과 같은 데이터: make loadtest-seed FILE=<경로>"
    "${CLIENT[@]}" loadtest-client seed --count "$SEED"
    CURRENT=$(active_fingerprints)
    echo "시드 후 활성 악성 지문: ${CURRENT}건"
fi

# ---- 4. 측정 --------------------------------------------------------------------
TS=$(date -u +%Y%m%dT%H%M%SZ)
OUT_HOST="loadtest-results/${TS}"
mkdir -p "$OUT_HOST"
GIT_REV=$(git rev-parse --short HEAD 2>/dev/null || echo unknown)
GIT_DIRTY=$( [ -n "$(git status --porcelain 2>/dev/null)" ] && echo yes || echo no )

ENV_ARGS=(-e LOADTEST_ENV_GIT_REV="$GIT_REV" -e LOADTEST_ENV_GIT_DIRTY="$GIT_DIRTY"
          -e LOADTEST_ENV_DOCKER_NCPU="$NCPU" -e LOADTEST_ENV_DOCKER_MEM_MB="$MEM_MB"
          -e LOADTEST_ENV_SEED_FINGERPRINTS="$CURRENT" -e LOADTEST_ENV_HOST_OS="$(uname -s)"
          -e LOADTEST_ENV_CPU_MODE="$( [ -n "${CPU_SPLIT:-}" ] && echo split || echo shared )")

if [ "$MODE" = burst ]; then
    say "버스트 시작: ${SIZE}MB × ${BURST}건 동시 → ${OUT_HOST}"
    "${CLIENT[@]}" "${ENV_ARGS[@]}" loadtest-client burst --size "$SIZE" --burst "$BURST" --bw "${BW:-none}" \
        --slo "$SLO" --timeout "$TIMEOUT" --out "/results/${TS}" --out-display "${OUT_HOST}"

    # 응답 출처와 별개로, DB에 엔진 분석 기록(analyses)이 업로드한 SHA마다 남았는지 센다.
    SHAS="${OUT_HOST}/burst_shas.txt"
    if [ -s "$SHAS" ]; then
        IN_LIST=$(sed "s/.*/'&'/" "$SHAS" | paste -sd, -)
        DB=$(psql_q -F ' ' -c "SELECT count(DISTINCT sha256), count(DISTINCT sha256) FILTER (WHERE status = 'SUCCESS')
                               FROM analyses WHERE sha256 IN (${IN_LIST})")
        set -- $DB
        echo "DB 검증(analyses): 엔진 분석 기록이 있는 SHA ${1}/${BURST}개, 그중 YARA 성공(status=SUCCESS) ${2}개"
        printf '{"analyses_distinct_sha": %s, "analyses_success_sha": %s, "burst": %s}\n' "$1" "$2" "$BURST" \
            > "${OUT_HOST}/db_check.json"
    fi
else
    say "측정 시작 → ${OUT_HOST}"
    "${CLIENT[@]}" "${ENV_ARGS[@]}" \
        loadtest-client bench --sizes "$SIZE" --concs "$CONC" --rates "$RATE" --duration "$DURATION" \
        --arrival "$ARRIVAL" --arrival-seed "$ARRIVAL_SEED" --max-inflight "$MAX_INFLIGHT" --bws "$BW" --count "$COUNT" \
        --warmup "$WARMUP" --slo "$SLO" ${QUICK:+--quick} \
        --timeout "$TIMEOUT" --out "/results/${TS}" --out-display "${OUT_HOST}"
fi

echo
echo "서버는 켜 둔 채로 둔다(다음 실행이 빠르다). 끌 때: make loadtest-down"
