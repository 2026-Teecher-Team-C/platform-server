#!/bin/sh
# 로컬·운영 nginx 설정을 nginx -t로 검사한다. 운영 설정은 임시 자체 서명 인증서를 운영 경로에 붙여 검사한다.
# 사용: infra/scripts/check-nginx.sh   (레포 루트에서, Docker 필요)
set -eu
cd "$(dirname "$0")/../nginx"
IMAGE=nginx:1.29-alpine
docker run --rm --add-host api-server:127.0.0.1 \
  -v "$PWD/nginx.conf:/etc/nginx/nginx.conf:ro" -v "$PWD/locations.conf:/etc/nginx/locations.conf:ro" \
  "$IMAGE" nginx -t
CERTS=$(mktemp -d)
trap 'rm -rf "$CERTS"' EXIT
LIVE="$CERTS/live/teamc-platform.duckdns.org"
mkdir -p "$LIVE"
docker run --rm -v "$CERTS:/certs" alpine/openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
  -subj /CN=check -keyout /certs/live/teamc-platform.duckdns.org/privkey.pem \
  -out /certs/live/teamc-platform.duckdns.org/fullchain.pem >/dev/null 2>&1
docker run --rm --add-host api-server:127.0.0.1 \
  -v "$PWD/nginx.prod.conf:/etc/nginx/nginx.conf:ro" -v "$PWD/locations.conf:/etc/nginx/locations.conf:ro" \
  -v "$CERTS:/etc/letsencrypt:ro" "$IMAGE" nginx -t
