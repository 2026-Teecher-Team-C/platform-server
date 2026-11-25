#!/usr/bin/env bash
# 운영 Postgres에서 SQL 파일을 실행하고 결과를 표준 출력으로 낸다.
#   bash prod_sql.sh sql/export_evidence.sql agent_id=<uuid> > out/evidence-p1.csv
# ssh 별칭은 QUALITY_SSH_HOST(기본 teecher). DB 사용자·이름은 운영 컨테이너 환경 변수에서 읽는다.
set -euo pipefail
SQL=${1:?sql file}; shift
HOST=${QUALITY_SSH_HOST:-teecher}
VARS=""
for kv in "$@"; do VARS="$VARS -v $(printf '%q' "$kv")"; done
ssh "$HOST" "C=platform-postgres-1; docker exec -i \$C psql -U \$(docker exec \$C printenv POSTGRES_USER) \
    -d \$(docker exec \$C printenv POSTGRES_DB) -v ON_ERROR_STOP=1 -At -q $VARS" < "$SQL"
