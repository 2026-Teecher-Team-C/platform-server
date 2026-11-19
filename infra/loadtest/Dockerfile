# 부하 시험 클라이언트(가상 에이전트). 빌드 컨텍스트: 레포 루트 (proto/ 를 함께 읽는다)
# 버전은 detection-engine/uv.lock과 같게 둔다(같은 proto를 같은 protobuf로 생성).
FROM python:3.12-slim
RUN pip install --no-cache-dir grpcio==1.84.0 grpcio-tools==1.84.0 protobuf==7.36.2
WORKDIR /app
COPY proto /proto
RUN python -m grpc_tools.protoc -I /proto --python_out=/app --grpc_python_out=/app \
        $(find /proto -name '*.proto') \
    && find /app/teecher -type d -exec touch {}/__init__.py \;
COPY infra/loadtest/loadtest.py /app/loadtest.py
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
ENTRYPOINT ["python", "/app/loadtest.py"]
