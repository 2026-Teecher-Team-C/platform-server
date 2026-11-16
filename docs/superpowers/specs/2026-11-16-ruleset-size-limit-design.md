# 룰셋 업로드 상한을 32MiB로 올리기

## 왜
측정 0단계에서 실제 오픈소스 YARA 룰셋(YARA Forge `20261115`)을 운영 구성에 올려야 한다. 그런데 업로드 경로 전체가 1MiB에 묶여 있어서 core(7.7MB)도 올라가지 않는다.
- 측정 계획: `project/docs/advancement/2026-11-16-pipeline-latency-measurement.md`
- 데이터 준비: `project/docs/advancement/2026-11-16-realistic-test-data.md`

확인한 것 (yara-python 4.5.4, 엔진과 같은 1GiB / 1CPU 제한):

| 패키지 | 파일 크기 | 룰 수 | 컴파일 | 최대 메모리 |
|---|---|---|---|---|
| core | 7.7MB | 5,089 | 1.2초 | 92MB |
| full | 19.4MB | 11,852 | 3.9초 | 276MB |

필요한 모듈은 `pe`, `dotnet`, `hash`, `math`, `string`, `elf`, `console`이고, 모두 엔진 빌드에 들어 있다.

## 목표 / 비목표
- 목표: 콘솔 → nginx → api-server → 엔진까지 **32MiB 이하** 룰셋이 업로드·활성화된다. 상한은 설정값 하나로 바꿀 수 있다.
- 목표: 32MiB를 넘으면 지금처럼 거부되고, 안내 문구가 실제 상한을 말한다.
- 비목표: **룰셋 목록 조회가 버전마다 원문(`source`) 전체를 읽는 문제**는 이번에 고치지 않는다(사용자 결정). 측정에서 메모리 영향을 재고 고도화 후보로 다룬다.
- 비목표: 블룸 기대 개수 조정. 이미 설정값(`lookup.bloom-filter.expected-hash-count`)이라 환경변수로 바꾼다.

## 방식
설정값 하나로 모든 층을 맞춘다. 다른 방법은 맞지 않는다.
- 룰셋을 1MiB씩 쪼개는 방법: 활성 룰셋은 하나뿐이라 쓸 수 없다.
- 벤치에서만 임시로 푸는 방법: 운영에 룰셋을 둘 수 없다.

## 바꾸는 곳 (7개 층)

| 층 | 지금 | 바꾼 뒤 |
|---|---|---|
| 콘솔 `MAX_RULESET_BYTES` + 안내 문구 | 1MiB | 32MiB |
| nginx | `/api/` 전체 2m | `/api/` 2m 유지. `location = /api/admin/rulesets`만 33m |
| Spring multipart | 1MB / 2MB | 32MB / 33MB |
| `UploadRulesetUseCase.MAX_BYTES` + 에러 문구 | 1MiB 상수 | 설정값 `ruleset.max-source-bytes`(기본 33554432) |
| `GlobalExceptionHandler` 크기 초과 문구 | "1MiB 이하" | 설정값으로 만든 문구 |
| 엔진 `MAX_SOURCE_BYTES` | 1MiB 상수 | 환경변수 `ENGINE_MAX_RULES_BYTES`(기본 33554432) |
| 엔진 gRPC 서버 수신 크기 | 기본 4MiB | `grpc.max_receive_message_length` = 원문 상한 + 1MiB |
| 엔진 룰셋 RPC 시간 제한 (`GrpcEngineRulesetPortImpl`) | 10초 상수 | 설정값 `ruleset.engine-timeout`(기본 60초) |

## 이것만은 안 된다 (anti-goal)
- nginx의 `/api/` 전체 상한을 올리지 않는다. 다른 관리 API의 노출 범위를 넓히지 않기 위해서다.
- 에이전트 판정 경로(`VerdictService`, 512m)와 엔진 파일 상한(`ENGINE_MAX_FILE_BYTES`)은 건드리지 않는다.

## 빌더에게 맡기는 것
- 설정값을 넘기는 방법(`@Value`이든 `@ConfigurationProperties`든). 기존 패턴을 따른다.
- 테스트 이름과 위치. 아래 항목을 테스트로 확인한다.
  - 1MiB를 넘고 32MiB 이하인 룰셋이 업로드·활성화되는가
  - 32MiB를 넘으면 400이 나는가
  - 엔진도 32MiB까지 컴파일하고, 그 이상은 거부하는가
