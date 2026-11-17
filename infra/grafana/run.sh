#!/usr/bin/env bash
# 맥북에서 고도화 테스트 대시보드를 띄운다. EC2 Prometheus(127.0.0.1:9090)로 SSH 터널을 열고 Grafana를 3000에 띄운다.
# 사용: infra/grafana/run.sh [ssh 호스트, 기본 teecher]   종료: infra/grafana/run.sh stop
set -euo pipefail
HOST="${1:-teecher}"
DIR="$(cd "$(dirname "$0")" && pwd)"
if [ "$HOST" = stop ]; then
  docker rm -f teecher-grafana >/dev/null 2>&1 || true
  pkill -f "ssh -f -N -L 9090:localhost:9090" || true
  exit 0
fi
lsof -nP -iTCP:9090 -sTCP:LISTEN >/dev/null 2>&1 || ssh -f -N -o ExitOnForwardFailure=yes -L 9090:localhost:9090 "$HOST"
docker rm -f teecher-grafana >/dev/null 2>&1 || true
docker run -d --name teecher-grafana -p 127.0.0.1:3000:3000 \
  -e GF_AUTH_ANONYMOUS_ENABLED=true -e GF_AUTH_ANONYMOUS_ORG_ROLE=Admin -e GF_AUTH_DISABLE_LOGIN_FORM=true \
  -v "$DIR/provisioning:/etc/grafana/provisioning:ro" -v "$DIR/dashboards:/var/lib/grafana/dashboards:ro" \
  grafana/grafana:11.2.0 >/dev/null
echo "Grafana: http://localhost:3000/d/advancement-test"
