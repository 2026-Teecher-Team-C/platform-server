#!/usr/bin/env bash
# 대시보드 규모 측정 실행기. make dashboard-scale-* 가 부른다(레포 루트에서). Mac·Linux·Windows(WSL) bash.
#
#   run.sh up          스택 기동(운영 사양 제한, 프로젝트 platform-dashscale)
#   run.sh blacklist   117만 건 악성 지문 시드(infra/loadtest/seed.sh를 프로젝트·DB 이름만 바꿔 호출)
#   run.sh bench       한 단계: 생성 → VACUUM (ANALYZE) + autovacuum 대기 → 기준선 → 폴링 1 → 폴링 5 → docker stats → auto_explain 로그
#   run.sh down        컨테이너 정리(볼륨은 남긴다)
#
# 환경 변수: PCS(bench 필수), DAYS=180, PER_DAY=20, SEED=0.42, AUTOVACUUM_WAIT_S=1200, QUICK(비우지 않으면 기간 7일·n=3·폴링 2라운드), FILE, SEED_SHA256
set -euo pipefail

cd "$(dirname "$0")/../.."
export LOADTEST_PROJECT=platform-dashscale
export LOADTEST_DB=platform_dashscale
export LOADTEST_EXTRA_COMPOSE=infra/compose.dashboard-scale.yml
. infra/loadtest/lib.sh

OUT_ROOT=infra/dashboard-scale/out
DAYS=${DAYS:-180}
PER_DAY=${PER_DAY:-20}
SEED=${SEED:-0.42}
QUICK=${QUICK:-}
AUTOVACUUM_WAIT_S=${AUTOVACUUM_WAIT_S:-1200}   # VACUUM 뒤 autovacuum 워커가 사라질 때까지 기다리는 상한(20분)

compose_files
CLIENT_PROFILES=(--profile loadtest-client --profile dashscale-client)

cmd_up() {
    precheck
    start_stack
    "${COMPOSE[@]}" --profile dashscale-client build dashscale-client
    echo "DB: ${LOADTEST_DB}, 활성 악성 지문: $(active_fingerprints)건"
}

cmd_blacklist() {
    FILE="${FILE:-}" SEED_SHA256="${SEED_SHA256:-$DEFAULT_SEED_SHA256}" bash infra/loadtest/seed.sh
}

cmd_down() {
    "${COMPOSE[@]}" "${CLIENT_PROFILES[@]}" down
}

cmd_bench() {
    [ -n "${PCS:-}" ] || die "PCS를 준다(예: PCS=1000)."
    case "$PCS" in ''|*[!0-9]*) die "PCS는 정수다: $PCS" ;; esac
    precheck
    [ "$(psql_q -c "SELECT current_database()" | tr -d '[:space:]')" = "$LOADTEST_DB" ] || die "DB가 ${LOADTEST_DB}가 아니다."
    # 실측 시작 때 스모크용 가짜 행(reason='dashscale-fixture')을 지운다. DB 이름 확인을 통과한 뒤에만 한다. 볼륨은 건드리지 않는다.
    if [ -z "${FIXTURE_BLACKLIST:-}" ]; then
        local gone
        gone=$(psql_q -c "WITH d AS (DELETE FROM hash_blacklist WHERE reason IN ('dashscale-fixture', 'fixture') RETURNING 1) SELECT count(*) FROM d" | tr -d '[:space:]')
        [ "$gone" = 0 ] || echo "확인용 블랙리스트 ${gone}건 삭제(reason=dashscale-fixture)"
    fi
    local bl
    bl=$(psql_q -c "SELECT count(*) FROM hash_blacklist WHERE hash_type='SHA256' AND is_active" | tr -d '[:space:]')
    if [ "$bl" -eq 0 ] && [ -n "${FIXTURE_BLACKLIST:-}" ]; then
        # 작은 확인용(스모크): 117만 건 시드 대신 가짜 해시 50건. 측정 결과로 쓰지 않는다.
        say "확인용 블랙리스트 50건 삽입(FIXTURE_BLACKLIST)"
        psql_q -c "INSERT INTO hash_blacklist (hash_type, hash_value, reason, severity, source, is_active, created_at)
                   SELECT 'SHA256', md5(g::text) || md5((g+1000)::text), 'dashscale-fixture', 'HIGH', 'IMPORT', true, now() FROM generate_series(1,50) g"
        bl=50
    fi
    [ "$bl" -gt 0 ] || die "활성 블랙리스트가 없다. make dashboard-scale-seed (117만 건)를 먼저 하거나, 작은 확인용이면 FIXTURE_BLACKLIST=1 을 준다."

    local stage="pcs-${PCS}" out="${OUT_ROOT}/pcs-${PCS}" t0
    mkdir -p "$out"
    rm -f "$out/${stage}-"*.csv "$out/baseline.csv" "$out/polling-1.csv" "$out/polling-5.csv" "$out/summary.csv" "$out/stats.csv" "$out/auto_explain.log"
    t0=$(date -u +%Y-%m-%dT%H:%M:%SZ)

    local RUN=("${COMPOSE[@]}" "${CLIENT_PROFILES[@]}" run --rm --no-deps -T --user "$(id -u):$(id -g)")
    local bench_args=() poll_rounds=24
    if [ -n "$QUICK" ]; then bench_args=(--periods 7 -n 3 --warmup 1); poll_rounds=2; fi

    say "1/5 생성: PC ${PCS}, ${DAYS}일, PC당 하루 ${PER_DAY}건 (기준 시각 ${t0})"
    # 앞 단계에서 끊긴 쿼리가 남아 TRUNCATE와 교착 나지 않게, 시드 전에 이 DB의 다른 세션을 끝내고 유휴를 확인한다.
    "${RUN[@]}" dashscale-client psql -v ON_ERROR_STOP=1 -v "expect_db=${LOADTEST_DB}" -f quiesce.sql
    local T=$SECONDS
    "${RUN[@]}" dashscale-client psql -v ON_ERROR_STOP=1 -v confirm_wipe=yes -v "expect_db=${LOADTEST_DB}" \
        -v "pcs=${PCS}" -v "days=${DAYS}" -v "per_day=${PER_DAY}" -v "seed=${SEED}" -v "now='${t0}'" -f seed.sql
    echo "생성 $((SECONDS - T))초, download_events: $(psql_q -c 'SELECT count(*) FROM download_events')행"

    say "2/5 VACUUM (ANALYZE) + autovacuum 대기"
    # 막 만든 표는 visibility map이 비어 index-only scan이 안 되고, 끝나지 않은 autovacuum이 측정 중 CPU·I/O를 쓴다.
    # 그래서 시드한 표를 직접 VACUUM한 뒤 autovacuum 워커가 하나도 없을 때까지 기다린다.
    T=$SECONDS
    local tbl
    for tbl in download_events analyses analysis_matches file_verdicts download_event_acknowledgements \
               event_decision_evidence similarity_assessments agents devices; do
        psql_q -c "VACUUM (ANALYZE) ${tbl}"
    done
    local av
    while :; do
        av=$(psql_q -c "SELECT count(*) FROM pg_stat_activity WHERE backend_type = 'autovacuum worker' AND datname = current_database()" | tr -d '[:space:]')
        [ "$av" = 0 ] && break
        [ $((SECONDS - T)) -lt "$AUTOVACUUM_WAIT_S" ] || die "autovacuum이 ${AUTOVACUUM_WAIT_S}초 안에 끝나지 않았다(워커 ${av}개)."
        sleep 5
    done
    echo "VACUUM + autovacuum 대기 $((SECONDS - T))초"

    local t_log
    t_log=$(date -u +%Y-%m-%dT%H:%M:%SZ)   # auto_explain 로그는 시드·ANALYZE가 끝난 이 시각부터만 저장한다

    # 측정 중 컨테이너 자원(docker stats)을 5초마다 stats.csv에 쌓는다.
    echo "ts,name,cpu_pct,mem_usage,mem_pct" > "$out/stats.csv"
    ( while true; do
          docker stats --no-stream --format "$(date -u +%H:%M:%S),{{.Name}},{{.CPUPerc}},{{.MemUsage}},{{.MemPerc}}" \
              $("${COMPOSE[@]}" ps -q postgres api-server nginx detection-engine redis) 2>/dev/null >> "$out/stats.csv" || true
          sleep 5
      done ) 2>/dev/null &
    local sampler=$!
    trap 'kill $sampler 2>/dev/null || true' EXIT

    # bench.py는 <stage>-<종류>.csv를 쓴다. 출력 디렉터리는 단계별 폴더(out/pcs-N)다.
    local B=(python bench.py --base http://nginx --out "out/pcs-${PCS}")
    say "3/5 기준선(동시 1)"
    "${RUN[@]}" dashscale-client "${B[@]}" --stage "$stage" --scenario baseline ${bench_args[@]+"${bench_args[@]}"}
    say "4/5 폴링: 관리자 1명"
    "${RUN[@]}" dashscale-client "${B[@]}" --stage "$stage" --scenario polling --admins 1 --rounds "$poll_rounds"
    mv "$out/${stage}-polling.csv" "$out/polling-1.csv"   # 5명 실행이 같은 파일 이름을 덮어쓰지 않게
    say "5/5 폴링: 관리자 5명"
    "${RUN[@]}" dashscale-client "${B[@]}" --stage "$stage" --scenario polling --admins 5 --rounds "$poll_rounds"
    mv "$out/${stage}-polling.csv" "$out/polling-5.csv"

    kill "$sampler" 2>/dev/null || true
    wait "$sampler" 2>/dev/null || true
    trap - EXIT
    "${COMPOSE[@]}" logs --no-color --since "$t_log" postgres > "$out/auto_explain.log" 2>&1 || true

    # bench.py 접두어(<stage>-)를 뗀 이름으로 정리한다: baseline.csv, polling-1.csv, polling-5.csv, summary.csv
    mv "$out/${stage}-baseline.csv" "$out/baseline.csv"
    mv "$out/${stage}-summary.csv" "$out/summary.csv"
    echo "결과: $out ($(ls "$out" | tr '\n' ' '))"
}

case "${1:-}" in
    up) cmd_up ;;
    blacklist) cmd_blacklist ;;
    bench) cmd_bench ;;
    down) cmd_down ;;
    *) die "사용: run.sh up|blacklist|bench|down" ;;
esac
