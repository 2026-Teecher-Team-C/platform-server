#!/usr/bin/env sh
# 레포 루트 proto/ 에서 Python 코드를 생성한다. 결과(src/teecher/)는 커밋하지 않는다.
set -eu
cd "$(dirname "$0")/.."
PROTO_ROOT="${PROTO_ROOT:-../proto}"
uv run --group dev python -m grpc_tools.protoc \
    -I "$PROTO_ROOT" \
    --python_out=src --pyi_out=src --grpc_python_out=src \
    $(find "$PROTO_ROOT" -name '*.proto')
find src/teecher -type d -exec touch {}/__init__.py \;
