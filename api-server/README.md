# api-server

Java 21 · Spring Boot 4.1 · Spring MVC + JPA · gRPC 서버(9090) · Flyway · PostgreSQL

- 8080: REST + SSE (관리 콘솔)
- 9090: gRPC (에이전트). 첫 gRPC 서비스 빈이 등록되어야 서버가 뜬다
- gRPC 코드는 레포 루트 `proto/`에서 빌드 시 생성된다

## 패키지 구조 (DDD)

```
com.teecherteamc.platform
├── verdict      판정 캐시 · 검사 기록           file_verdicts, analyses, analysis_matches
├── hashlist     블랙/화이트리스트                hash_blacklist, hash_whitelist
├── quarantine   S3 격리 · 오탐 복원              quarantine_files
├── policy       바이패스 · 파일 타입 정책         bypass_domains, file_type_policies
├── ruleset      YARA 룰셋                        yara_rulesets, yara_rules
├── fleet        장비 · 에이전트                   devices, agents
├── event        다운로드 이벤트                   download_events
├── audit        감사 로그                        audit_logs
├── admin        관리자 계정                       admin_users
└── lookup       해시 조회 사슬 (읽기 전용, 도메인 모델을 거치지 않음)
```

각 컨텍스트 안:

| 패키지 | 담는 것 |
|---|---|
| `domain` | 애그리거트 · 값 객체 · 도메인 이벤트 · 리포지토리 인터페이스 |
| `application` | 유스케이스(트랜잭션 경계), 이벤트 핸들러 |
| `infrastructure` | JPA 리포지토리 구현, Redis, S3, 외부 연동 |
| `interfaces` | REST 컨트롤러, gRPC 서비스 |

## 테스트

Testcontainers가 PostgreSQL을 띄워 Flyway 마이그레이션까지 적용한다. 레포 루트에서 `make test-api`.
