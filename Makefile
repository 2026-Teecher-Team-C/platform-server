COMPOSE_DEV = docker compose --env-file infra/dev.env -f infra/compose.yml -f infra/compose.dev.yml
GRADLE = docker run --rm -v /var/run/docker.sock:/var/run/docker.sock \
	-e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
	-v "$(CURDIR)":/workspace -v platform-gradle-cache:/root/.gradle \
	-w /workspace/api-server eclipse-temurin:21-jdk ./gradlew --no-daemon
ENGINE_TEST = docker run --rm -v "$(CURDIR)":/workspace -w /workspace/detection-engine \
	-e UV_PROJECT_ENVIRONMENT=/tmp/venv ghcr.io/astral-sh/uv:0.9-python3.12-bookworm-slim sh -c
NODE = docker run --rm -v "$(CURDIR)/console":/app -v platform-console-node-modules:/app/node_modules -w /app node:22-alpine sh -c

.PHONY: dev down logs test test-api test-engine test-console proto bench-tlsh loadtest loadtest-burst loadtest-seed loadtest-down loadtest-logs test-quality quality

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
	$(NODE) "npm ci && npm run lint && npm test && npm run build"

bench-tlsh:     ## TLSH corpus 비교 JMH 벤치마크 (예: make bench-tlsh ARGS="-p corpusSize=1000")
	$(GRADLE) jmh -PjmhArgs="$(ARGS)"

proto:          ## proto 변경 후 Java·Python 코드 재생성
	$(GRADLE) generateProto
	$(ENGINE_TEST) "./scripts/gen_proto.sh"

# ---- 로컬 부하 시험(업로드 + YARA 판정). 사용법: infra/loadtest/README.md ----
LOADTEST_COMPOSE = docker compose -p platform-loadtest --env-file infra/dev.env \
	-f infra/compose.yml -f infra/compose.temp-db.yml -f infra/compose.loadtest.yml
SIZE ?= 10
CONC ?=
RATE ?=
DURATION ?= 0
ARRIVAL ?= poisson
ARRIVAL_SEED ?= 1
MAX_INFLIGHT ?= 200
BW ?=
COUNT ?= 0
QUICK ?=
WARMUP ?= -1
SLO ?= 10
SEED ?= 50000
TIMEOUT ?= 120
BURST ?= 300
CPU_SPLIT ?=
FILE ?=
SEED_SHA256 ?=

loadtest:       ## 운영 사양(2 vCPU/3.8GB)으로 묶은 서버에 동시 업로드 (예: make loadtest SIZE=100 CONC=1,4 BW=50mbit / RATE=0.5,1 DURATION=180)
	SIZE="$(SIZE)" CONC="$(CONC)" RATE="$(RATE)" DURATION="$(DURATION)" ARRIVAL="$(ARRIVAL)" \
		ARRIVAL_SEED="$(ARRIVAL_SEED)" MAX_INFLIGHT="$(MAX_INFLIGHT)" BW="$(BW)" COUNT="$(COUNT)" QUICK="$(QUICK)" WARMUP="$(WARMUP)" SLO="$(SLO)" SEED="$(SEED)" TIMEOUT="$(TIMEOUT)" \
		CPU_SPLIT="$(CPU_SPLIT)" FILE="$(FILE)" SEED_SHA256="$(SEED_SHA256)" bash infra/loadtest/run.sh

loadtest-burst: ## 처음 보는 파일 BURST개(기본 10MB × 300)를 한꺼번에 올려 판정 시간을 잰다(로컬 전용)
	MODE=burst SIZE="$(SIZE)" BURST="$(BURST)" BW="$(BW)" SLO="$(SLO)" SEED="$(SEED)" TIMEOUT="$(TIMEOUT)" \
		CPU_SPLIT="$(CPU_SPLIT)" FILE="$(FILE)" SEED_SHA256="$(SEED_SHA256)" bash infra/loadtest/run.sh

loadtest-seed:  ## 운영과 같은 악성 지문 시드 파일을 로컬 부하 시험 DB에 넣는다 (예: make loadtest-seed FILE=~/Downloads/malicious-fingerprints-2026-11-19.csv.gz)
	CPU_SPLIT="$(CPU_SPLIT)" FILE="$(FILE)" SEED_SHA256="$(SEED_SHA256)" bash infra/loadtest/seed.sh

loadtest-down:  ## 부하 시험 컨테이너 정리(시드한 DB 볼륨은 남긴다. 지우려면 LOADTEST_COMPOSE ... down -v)
	$(LOADTEST_COMPOSE) --profile loadtest-client down

loadtest-logs:
	$(LOADTEST_COMPOSE) logs -f api-server detection-engine

QUALITY_IMAGE = platform-quality
QUALITY_BUILD = docker build -q -f infra/quality/Dockerfile -t $(QUALITY_IMAGE) . >/dev/null

test-quality:   ## 탐지 품질 측정 하네스 테스트
	$(QUALITY_BUILD)
	docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "$(CURDIR)/infra/quality":/app \
		-v "$(CURDIR)/api-server/src/main/resources/db/migration":/migrations:ro \
		--entrypoint pytest $(QUALITY_IMAGE) -q tests

quality:        ## 탐지 품질 측정 (예: make quality ARGS="build-malicious --csv out/full.csv.zip ..."), 런북은 infra/quality/README.md
	$(QUALITY_BUILD)
	docker run --rm -it -v "$(CURDIR)/infra/quality":/app -v "$$HOME/.config/abusech":/root/.config/abusech:ro \
		-e QUALITY_ADMIN_USER -e QUALITY_ADMIN_PASSWORD $(QUALITY_IMAGE) $(ARGS)
