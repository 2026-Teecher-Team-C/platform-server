# 대시보드 규모 측정 도구(목데이터 생성 시험 + 측정 클라이언트). 빌드 컨텍스트: infra/dashboard-scale
FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends postgresql-client \
    && rm -rf /var/lib/apt/lists/*
RUN pip install --no-cache-dir httpx==0.28.1 "psycopg[binary]==3.2.3" pytest==8.4.2 testcontainers==4.13.2
ENV PYTHONPATH=/app PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
WORKDIR /app
