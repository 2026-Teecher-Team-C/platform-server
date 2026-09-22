# platform-server

프록시 기반 악성코드 다운로드 탐지 플랫폼의 **서버 쪽** — 검사 서버(API + 탐지 엔진), 관리 콘솔, 인프라.

| 레포 | 내용 |
|---|---|
| **platform-server** (여기) | API 서버 · 탐지 엔진 · 관리 콘솔 · 인프라 · gRPC 계약(`proto/`) 원본 |
| [platform-agent](https://github.com/2026-Teecher-Team-C/platform-agent) | 사용자 PC의 로컬 에이전트(프록시). `proto/`를 submodule로 가져간다 |
| [project](https://github.com/2026-Teecher-Team-C/project) | 설계 문서. 왜 이 구조인지는 여기서 본다 |

## 구조

```
proto/              gRPC 계약 원본 (에이전트 ↔ API, API ↔ 엔진)
api-server/         Java 21 · Spring Boot 4.1 · MVC + JPA · gRPC(9090) · Flyway — DDD
detection-engine/   Python 3.12 · gRPC(50051) · YARA — API 서버하고만 통신
console/            React · Vite · TypeScript — Vercel 배포
infra/              docker compose(운영/개발) · nginx · prometheus
```

## 로컬 개발

Docker만 있으면 된다. JDK·Python·Node를 로컬에 설치하지 않아도 된다.

```bash
make dev     # 전체 스택 기동
make test    # api-server · detection-engine · console 테스트
make proto   # proto 수정 후 코드 재생성
make down
```

| 서비스 | 주소 |
|---|---|
| API (REST·SSE / gRPC) | http://localhost:8080 / localhost:9090 |
| nginx (운영과 같은 경로) | http://localhost |
| 콘솔 | http://localhost:5173 |
| PostgreSQL / Redis | localhost:5432 / 6379 (`platform` / `platform`) |
| Prometheus | http://localhost:9091 |

IntelliJ로 열면 `.idea/codeStyles`의 우테코 스타일이 자동 적용된다. 원본: `config/intellij-java-wooteco-style.xml`.

## 브랜치

- `develop`: 기본 브랜치. 기능 브랜치(`feat/…`, `fix/…`)는 여기로 PR
- `main`: 배포 브랜치. `develop` → `main` 머지 후 `deploy` 워크플로를 수동 실행
- PR은 승인 1명 + CI 통과가 필요하다

## proto를 바꿀 때

`proto/`는 에이전트 레포가 태그로 고정해 쓴다. 호환이 깨지는 변경(필드 번호 재사용, 타입 변경, 삭제)은
하지 않는다. 바꾼 뒤 `proto-vX.Y.Z` 태그를 달고, 에이전트 레포에서 submodule을 그 태그로 올린다.
