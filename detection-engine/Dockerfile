# 빌드 컨텍스트: 레포 루트 (proto/ 를 함께 읽는다)
FROM python:3.12-slim AS build
COPY --from=ghcr.io/astral-sh/uv:0.9 /uv /usr/local/bin/uv
WORKDIR /app
ENV UV_PROJECT_ENVIRONMENT=/opt/venv UV_COMPILE_BYTECODE=1

COPY detection-engine/pyproject.toml detection-engine/uv.lock ./
# 런타임 venv와 코드 생성용 venv를 분리한다. 둘 다 uv.lock 버전을 따르므로 protobuf 버전이 어긋나지 않는다.
RUN uv sync --frozen --no-dev --no-install-project \
    && UV_PROJECT_ENVIRONMENT=/opt/genvenv uv sync --frozen --no-install-project

COPY proto /proto
RUN mkdir -p /gen \
    && /opt/genvenv/bin/python -m grpc_tools.protoc -I /proto --python_out=/gen --grpc_python_out=/gen \
        $(find /proto -name '*.proto') \
    && find /gen/teecher -type d -exec touch {}/__init__.py \;

FROM python:3.12-slim
WORKDIR /app
ENV PYTHONPATH=/app/src PYTHONDONTWRITEBYTECODE=1 ENGINE_RULES_DIR=/app/rules
COPY --from=build /opt/venv /opt/venv
COPY --from=build /gen/teecher ./src/teecher
COPY detection-engine/src ./src
COPY detection-engine/rules ./rules

RUN useradd --system --uid 10001 engine
USER engine
EXPOSE 50051
CMD ["/opt/venv/bin/python", "-m", "detection_engine.server"]
