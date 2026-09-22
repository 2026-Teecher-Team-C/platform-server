COMPOSE_DEV = docker compose --env-file infra/dev.env -f infra/compose.yml -f infra/compose.dev.yml
GRADLE = docker run --rm -v /var/run/docker.sock:/var/run/docker.sock \
	-e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
	-v "$(CURDIR)":/workspace -v platform-gradle-cache:/root/.gradle \
	-w /workspace/api-server eclipse-temurin:21-jdk ./gradlew --no-daemon
ENGINE_TEST = docker run --rm -v "$(CURDIR)":/workspace -w /workspace/detection-engine \
	-e UV_PROJECT_ENVIRONMENT=/tmp/venv ghcr.io/astral-sh/uv:0.9-python3.12-bookworm-slim sh -c
NODE = docker run --rm -v "$(CURDIR)/console":/app -v platform-console-node-modules:/app/node_modules -w /app node:22-alpine sh -c

.PHONY: dev down logs test test-api test-engine test-console proto

dev:            ## 전체 스택 기동 (api 8080/9090, console 5173, postgres 5432, redis 6379, prometheus 9091)
	$(COMPOSE_DEV) up --build

down:
	$(COMPOSE_DEV) down

logs:
	$(COMPOSE_DEV) logs -f

test: test-api test-engine test-console

test-api:
	$(GRADLE) test

test-engine:
	$(ENGINE_TEST) "./scripts/gen_proto.sh && uv run --group dev ruff check . && uv run --group dev pytest -q"

test-console:
	$(NODE) "npm ci && npm run lint && npm run build"

proto:          ## proto 변경 후 Java·Python 코드 재생성
	$(GRADLE) generateProto
	$(ENGINE_TEST) "./scripts/gen_proto.sh"
