# 탐지 품질 측정 하네스. 빌드 컨텍스트: 레포 루트 (proto/ 를 함께 읽는다)
# grpc·protobuf 버전은 infra/loadtest/Dockerfile과 같게, py-tlsh는 에이전트(platform-agent)와 같은 라이브러리를 쓴다.
FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends p7zip-full curl ca-certificates openssh-client g++ \
    && rm -rf /var/lib/apt/lists/*
RUN pip install --no-cache-dir grpcio==1.84.0 grpcio-tools==1.84.0 protobuf==7.36.2 py-tlsh==5.0.0 pytest==8.4.2
WORKDIR /gen
COPY proto /proto
RUN python -m grpc_tools.protoc -I /proto --python_out=/gen --grpc_python_out=/gen \
        $(find /proto -name '*.proto') \
    && find /gen/teecher -type d -exec touch {}/__init__.py \;
ENV PYTHONPATH=/gen:/app PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
WORKDIR /app
COPY infra/quality /app
ENTRYPOINT ["python", "-m", "quality.cli"]
