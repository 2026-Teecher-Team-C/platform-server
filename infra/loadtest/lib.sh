#!/usr/bin/env bash
# run.sh·seed.sh가 함께 쓰는 함수. 직접 실행하지 않는다(source 전용).
# Mac·Linux·Windows(WSL) bash. GNU/BSD 차이를 피하려고 date·sed·readlink의 확장 옵션을 쓰지 않는다.

# 운영과 같은 기본 시드 파일(팀 공유 드라이브에서 받는다, 레포에 커밋하지 않는다).
DEFAULT_SEED_SHA256=19ce12a524859e9683a178b1190cd3a5c56b8357395ab54d40eb5d961692bf44
SERVER_SERVICES=(nginx api-server detection-engine postgres redis localstack toxiproxy)

say() { printf '\n==> %s\n' "$*"; }
die() { printf '\n[중단] %s\n' "$*" >&2; exit 1; }

compose_files() {
    COMPOSE=(docker compose -p platform-loadtest --env-file infra/dev.env
             -f infra/compose.yml -f infra/compose.temp-db.yml -f infra/compose.loadtest.yml)
    if [ -n "${CPU_SPLIT:-}" ]; then
        COMPOSE+=(-f infra/compose.loadtest-split.yml)
    fi
}

# Docker 자원 점검 + CPU 배치 결정. 결과: NCPU, MEM_MB, LOADTEST_SERVER_CPUSET, LOADTEST_CLIENT_CPUSET
precheck() {
    command -v docker >/dev/null 2>&1 || die "docker 명령이 없다. Docker Desktop(Mac/Windows) 또는 Docker Engine(Linux)을 설치한다."
    docker compose version >/dev/null 2>&1 || die "docker compose(v2)가 없다. Docker Desktop을 최신으로 올리거나 compose 플러그인을 설치한다."
    local info
    info=$(docker info --format '{{.NCPU}} {{.MemTotal}}' 2>/dev/null) || die "Docker 데몬에 연결할 수 없다. Docker Desktop을 켠다."
    NCPU=${info%% *}
    MEM_MB=$(( ${info##* } / 1024 / 1024 ))
    echo "Docker 할당: CPU ${NCPU}개, 메모리 ${MEM_MB}MiB"
    # 서버 컨테이너가 CPU 2개·메모리 상한 합계 3520MiB를 쓴다. 여기에 클라이언트·toxiproxy·localstack이 더 붙는다.
    # Docker Desktop에서 4GB로 설정하면 VM 커널 몫을 빼고 약 3.8GiB로 보인다 — 그래서 하한을 3584MiB(3.5GiB)로 둔다.
    if [ "$NCPU" -lt 2 ]; then
        die "Docker에 할당된 CPU가 ${NCPU}개다. 서버를 운영 사양(2 vCPU)으로 묶으려면 2개 이상 필요하다(4개 이상 권장 — 클라이언트도 같은 VM에서 돈다).
  - Mac/Windows: Docker Desktop > Settings > Resources > CPUs
  - Windows(WSL2 백엔드): %UserProfile%\\.wslconfig 의 [wsl2] processors=4 후 'wsl --shutdown'
  - Linux: 호스트 CPU 수가 그대로 보인다"
    fi
    if [ "$MEM_MB" -lt 3584 ]; then
        die "Docker에 할당된 메모리가 ${MEM_MB}MiB다. 서버 상한 합계(3.5GiB)와 클라이언트를 올리려면 4GB 이상 필요하다(6GB 이상 권장).
  - Mac/Windows: Docker Desktop > Settings > Resources > Memory
  - Windows(WSL2 백엔드): %UserProfile%\\.wslconfig 의 [wsl2] memory=6GB 후 'wsl --shutdown'"
    fi
    # 서버는 CPU 0,1에 묶고, 클라이언트 쪽(클라이언트·toxiproxy·localstack)은 나머지에 둔다.
    export LOADTEST_SERVER_CPUSET=0,1
    if [ "$NCPU" -ge 3 ]; then
        export LOADTEST_CLIENT_CPUSET="2-$((NCPU - 1))"
    else
        export LOADTEST_CLIENT_CPUSET=0,1
        echo "주의: CPU 2개면 클라이언트·toxiproxy가 서버와 같은 CPU를 쓴다. 결과가 실제보다 느릴 수 있다(4개 이상 권장)."
    fi
    echo "CPU 배치: 서버 ${LOADTEST_SERVER_CPUSET} / 클라이언트 ${LOADTEST_CLIENT_CPUSET}${CPU_SPLIT:+ (CPU_SPLIT: 컨테이너별 cpus 상한)}"
}

# 서버를 빌드·기동하고 응답할 때까지 기다린다. 결과: CLIENT(가상 에이전트 실행 명령)
start_stack() {
    say "서버 빌드·기동 (운영 사양 제한, 프로젝트 platform-loadtest)"
    mkdir -p loadtest-results
    "${COMPOSE[@]}" up -d --build "${SERVER_SERVICES[@]}"
    "${COMPOSE[@]}" --profile loadtest-client build loadtest-client
    # 컨테이너는 결과 디렉터리에 현재 사용자로 쓴다(Linux에서 root 소유 파일이 남지 않게).
    CLIENT=("${COMPOSE[@]}" --profile loadtest-client run --rm --no-deps -T --user "$(id -u):$(id -g)")
    "${CLIENT[@]}" loadtest-client wait --timeout 600
}

psql_q() {
    "${COMPOSE[@]}" exec -T postgres psql -U platform -d platform -v ON_ERROR_STOP=1 -tA "$@"
}

active_fingerprints() {
    psql_q -c "SELECT count(*) FROM active_malicious_fingerprints" | tr -d '[:space:]'
}

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -d' ' -f1
    else
        shasum -a 256 "$1" | cut -d' ' -f1
    fi
}

# 기본 위치에서 시드 파일을 찾는다: <레포>/loadtest-seed/*.csv.gz, 다음으로 레포 옆 ../loadtest-seed/*.csv.gz
find_seed_file() {
    local f
    for f in loadtest-seed/*.csv.gz ../loadtest-seed/*.csv.gz; do
        if [ -f "$f" ]; then
            echo "$f"
            return 0
        fi
    done
    return 1
}
