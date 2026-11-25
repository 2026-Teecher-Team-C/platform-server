#!/usr/bin/env bash
# 운영 Postgres에서 SQL 파일을 실행하고 결과를 표준 출력으로 낸다.
#   bash prod_sql.sh sql/export_evidence.sql agent_id=<uuid> > out/evidence-p1.csv
# ssh 별칭은 QUALITY_SSH_HOST(기본 teecher). DB 사용자·이름은 컨테이너 환경 변수에서 읽는다.
# 로컬 시험: QUALITY_SSH_HOST=local QUALITY_PG_CONTAINER=platform-loadtest-postgres-1 bash prod_sql.sh ...
set -euo pipefail
SQL=${1:?sql file}; shift
HOST=${QUALITY_SSH_HOST:-teecher}
C=${QUALITY_PG_CONTAINER:-platform-postgres-1}
VARS=""
for kv in "$@"; do VARS="$VARS -v $(printf '%q' "$kv")"; done
CMD="docker exec -i $C psql -U \$(docker exec $C printenv POSTGRES_USER) -d \$(docker exec $C printenv POSTGRES_DB) \
    -v ON_ERROR_STOP=1 -At -q $VARS"
if [ "$HOST" = "local" ]; then
    bash -c "$CMD" < "$SQL"
else
    ssh "$HOST" "$CMD" < "$SQL"
fi
