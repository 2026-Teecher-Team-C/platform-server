# TLSH PR1 구현, 검증 보고서

## 상태와 작업 경계

- 작업 폴더: `/srv/project/T-teamc/platform-server-tlsh-fingerprints`
- 브랜치: `feat/tlsh-fingerprints`
- 시작, 현재 HEAD: `f7f83d0ef830219cb88a03bdaa0745ad2b1fa6a1`
- **Java API 서버 구현 유지로 확정했다.** 기존 SHA256/YARA 검사에서 통과한 파일에 TLSH 거리 비교를 추가하는 구조다. PR1은 모든 검증 업로드의 지문 계산과 저장만 구현하며, 판정용 거리 비교는 PR2 범위다. 현재 변경분은 미커밋이다.
- 중단 이후에는 기존 변경분 전체 테스트, 출처 확인, 보고서 정리만 수행했다. 임의로 Python으로 전환하거나 코드를 폐기하지 않았다.
- 커밋, push, PR, merge, 배포, Kanban 상태 변경 없음. 다른 worktree, 다른 profile 수정 없음.
- 원래 `CLAUDE.md`의 TLSH 미계산 설명은 새 지문 저장 기능 이전 상태다. 이 PR은 지문 준비만 추가하며 유사도 판정 연결은 후속 PR 범위다.

## 보존된 구현 결과

1. 공식 Trend Micro Java 소스 여섯 개를 별도 Gradle `:tlsh` 모듈로 소스 빌드한다. 새 알고리즘이나 추측한 Maven 좌표는 없다.
2. `SubmitFileUseCase`의 SHA256 검증 성공 뒤, 캐시 조기 반환 전에 스풀 파일을 8192바이트 버퍼로 계산하고 지문을 저장한다. 기존 판정 변환, YARA RPC 계약은 변경하지 않았다.
3. 불일치는 계산 전 DATA_LOSS 거부, 50바이트 미만은 `TOO_SHORT`, 낮은 분산은 `LOW_ENTROPY`, 계산 또는 파일 읽기 실패는 `UNAVAILABLE`, 정상 산출은 `CALCULATED`다. 실패 상태의 TLSH는 NULL이다.
4. 계산 불가만 기존 검사로 진행한다. DB 저장 오류는 전파해 다운로드를 허용하지 않으며, 엔진 장애의 fail-close도 유지한다. 파일 읽기는 DB 트랜잭션 밖, 저장은 단일 upsert다.
5. V6에서 `file_fingerprints`를 추가한다. SHA256 PK, TLSH, 상태, 알고리즘 식별자, 시간, 출처, 외부 출처 설명, 가져온 관리자 ID를 보존한다. 신규 `FileVerdict`보다 먼저 저장하므로 그 테이블을 참조하는 FK는 없다.
6. `active_malicious_fingerprints` 읽기 전용 뷰는 현재 활성 SHA256 블랙리스트와 지문을 조인하고 유효한 화이트리스트는 제외한다. 해제 후 즉시 후보에서 빠진다. **뷰를 판정 경로에서 사용하지 않는다.**
7. 관리자용 단일 지문 보강 API와 동일 트랜잭션 감사 로그를 추가했다. 블랙리스트 CRUD나 판정은 이 API에서 변경하지 않는다.

## 최소 권한 가져오기 계약

`POST /api/admin/file-fingerprints/import`

```json
{
  "sha256": "016ed067eb80b4b4a64d7c8c50631d3c5c9a7ba06feeba8797f82a93fe05a8f6",
  "tlsh": "T1A7D14D74F9407AE28FAE92B7CCFE6BA1509763248ED678CC9474B7C71593294FD00851",
  "provenance": "internal-reference-test"
}
```

- 공통 Bearer 인증, DB에서 현재 활성 `ADMIN` 확인. 일반 `VIEWER`는 403이다. `AUTH_ENABLED=false`인 기존 로컬 개발 모드에서는 기존 초기 관리자 주입 규칙을 따른다.
- SHA256은 소문자 hex 64자 그대로 검증한다. 공백을 제거하거나 잘못된 값을 고치지 않는다.
- TLSH는 대문자 T1 72자 또는 명시적으로 허용한 레거시 hex 70자만 허용한다. 레거시 입력은 저장 시 T1로 변환하고 공식 파서로 확인한다. TNULL, 다른 버전, 소문자, 길이 오류는 400이다.
- 출처 설명은 공백만 있는 값 불가, 최대 512자다. 형식 검사는 컨트롤러와 서비스 경계 양쪽에 있다.
- 현재 활성 SHA256 블랙리스트가 없으면 409다. 새 블랙리스트를 만들거나 비활성 항목을 활성화하지 않는다.
- 권한 행과 블랙리스트 행을 공유 잠금으로 읽어 해당 쓰기 트랜잭션 동안 변경과 직렬화한다.
- 서버가 직접 계산한 정상 지문은 가져오기로 덮어쓰지 않고 409를 반환한다. 기존 외부 지문은 명시적인 재가져오기로 갱신할 수 있다. 감사 로그는 가져오기와 같은 트랜잭션이다.
- 성공 204, `IMPORT_FILE_FINGERPRINT` / `FILE_FINGERPRINT` 감사 기록을 남긴다. 판정, 분석, 이벤트, 블룸, 캐시는 변경하지 않는다.
- SHA256와 TLSH가 실제 동일한 원본에서 나온 것인지는 원본 없이 암호학적으로 검증할 수 없다. 관리자가 신뢰한 공급자의 쌍이라는 계약이다. 형식과 현재 기준 샘플 자격을 검증하며, 원본 다운로드나 외부 피드 접속은 하지 않는다.
- 알고리즘 식별자는 현재 표준 파라미터와 통합 라이브러리 revision을 나타낸다. 외부 지문을 Java에서 직접 계산했다는 주장은 `ADMIN_IMPORT` 출처로 구분한다.

## 고정 라이브러리와 기준 구현 대조

- 저장소: https://github.com/trendmicro/tlsh
- 고정 revision: `ebdec8fde93a4ac359437f4f3796c78d3ae433bf`
- 라이선스: upstream의 Apache-2.0 OR BSD-3-Clause 중 **Apache-2.0 선택**. 원본 LICENSE, NOTICE, 소스 헤더를 보존했다. 생성 JAR의 `META-INF/LICENSE`, `META-INF/NOTICE.txt`도 실제 확인했다.
- 공식 Java README는 Maven Central 대신 소스 빌드를 안내한다. 별도 Gradle 모듈에서 변경 없는 Java 소스 여섯 개만 컴파일한다.
- 동일 revision의 공식 C++를 CMake로 실제 빌드했다. `tlsh_unittest -version` 출력: `5.0.2 compact hash  sliding_window=5`.
- 표준 128 buckets, 1-byte checksum, T1, 길이 포함 거리다. Java의 기본 최소 길이는 256이므로 공식 `force=true`로 최소 50바이트 모드를 사용한다.
- Java 소스 여섯 개를 upstream 원본과 bytes 비교한 결과 모두 동일했다.

무해한 고정 fixture `api-server/src/test/resources/tlsh/reference.txt`는 Python 표준 라이브러리로 다음처럼 생성했다. 악성 파일을 내려받지 않았다.

```python
''.join(hashlib.sha256(str(i).encode()).hexdigest() + '\n' for i in range(100))
```

공식 C++ 측정 결과를 Java 단위 테스트, 실제 gRPC 업로드 후 DB 지문과 비교했다.

| 입력 | C++ T1 결과 |
|---|---|
| 원본 6500바이트 | `T1A7D14D74F9407AE28FAE92B7CCFE6BA1509763248ED678CC9474B7C71593294FD00851` |
| 원본 앞 50바이트 | `T11890043150034750FF04F71557447000DD1033F110570D43540DD1DC50DC143DC47075` |
| 원본 + 공백 64바이트 | `T1AAD13C74B9407AE28FAA92B7CCFE6B61509363248EC668CCB46CB7C61793294FD00851` |
| 원본 5회 반복, 32500바이트 | `T1CBE24D74F9407AE28FAE92B7CCFE6BA1509763248ED678CC9474B7C71593294FD00851` |

원본 SHA256: `016ed067eb80b4b4a64d7c8c50631d3c5c9a7ba06feeba8797f82a93fe05a8f6`

패딩 SHA256: `2a9e20bd1685d2999c21045486dc7dfd0eed7999e852338a67ecff68511c0fd3`

공식 C++ `-c 원본 -f 패딩`과 Java `totalDiff(..., true)`의 실제 거리는 둘 다 **17**이다. 두 파일 모두 기존 CLEAN/ALLOW를 유지한다. 이 수치는 참조 구현 호환성 검증이지, 악성 탐지율이나 후속 정책 임계값 검증이 아니다.

재현 명령, API 폴더 기준:

```sh
git clone https://github.com/trendmicro/tlsh.git build/tlsh-reference
git -C build/tlsh-reference checkout ebdec8fde93a4ac359437f4f3796c78d3ae433bf
cmake -S build/tlsh-reference -B build/tlsh-reference-build
cmake --build build/tlsh-reference-build -j2
build/tlsh-reference/bin/tlsh_unittest -f src/test/resources/tlsh/reference.txt
```

## 실제 RED, GREEN 기록

명령은 모두 `api-server`에서 실행했다. 원본 로그는 처음 `.tlsh-work`에 작성했고, 소스 변경으로 오인되지 않도록 같은 worktree의 무시되는 `api-server/build/tlsh-evidence/`로 옮겨 보존했다.

| 수직 단계 | 실행 명령 | RED | GREEN |
|---|---|---|---|
| 검증된 업로드 지문 저장 | `./gradlew test --tests '*TlshFingerprintGrpcIntegrationTest'` | `red1.log`, 1개 실행, 1개 실패, 새 테이블 조회에서 PostgreSQL 오류 | `green1.log`, BUILD SUCCESSFUL |
| 관리자 지문 보강 | `./gradlew test --tests '*FingerprintImportRestIntegrationTest'` | `red2.log`, 1개 실행, 1개 실패, 기대한 가져오기 성공 응답 없음 | `green2.log`, BUILD SUCCESSFUL |
| VIEWER 가져오기 금지 | `./gradlew test --tests '*FingerprintImportRestIntegrationTest.viewer*'` | `red3.log`, 1개 실행, 1개 실패, 403 기대 불충족 | `green3.log`, 해당 클래스 BUILD SUCCESSFUL |
| 현재 활성 기준 샘플 뷰 | `./gradlew test --tests '*FingerprintImportRestIntegrationTest.candidates*'` | `red4.log`, 1개 실행, 1개 실패, 뷰 조회에서 PostgreSQL 오류 | `green4.log`, 해당 클래스 BUILD SUCCESSFUL |

그 뒤 캐시 보강, mismatch 무쓰기, 짧은 파일, 저분산, 실제 패딩, 50바이트 경계, 8KiB 경계, 파일 읽기 실패, 입력 오류, 레거시, 권한, 외부 지문 덮어쓰기 금지, DB 오류, 엔진 fail-close를 회귀 검증했다. 이 추가 회귀 테스트 모두가 독립 RED 단계를 거쳤다고 주장하지 않는다. 주요 새 동작은 위 네 수직 RED, GREEN 사이클로 구현했다.

## 실행 결과

- 시작 기준 `./gradlew test --tests '*SubmitFileGrpcIntegrationTest'`: BUILD SUCCESSFUL, `baseline.log`.
- 관련 네 클래스 `./gradlew test --tests '*TlshFingerprintGrpcIntegrationTest' --tests '*FingerprintImportRestIntegrationTest' --tests '*OfficialTlshCalculatorTest' --tests '*SubmitFileGrpcIntegrationTest'`: BUILD SUCCESSFUL, `related3.log`.
- 첫 전체 `./gradlew test`: **416개 실행, 1개 실패, skip 0**. 기존 `SchemaMigrationTest`가 테이블을 17개로 고정해 새 `file_fingerprints`를 예상하지 못했다. 기대 목록과 테스트 이름을 18개로 갱신했다. `full-suite.log`.
- 최종 전체 `./gradlew test`: **416개 실행, 실패 0, 오류 0, skip 0, BUILD SUCCESSFUL**. `full-suite-2.log`, `build/test-results/test/TEST-*.xml`에서 합산했다.
- 새 테스트: gRPC 지문 7개, 가져오기 REST 7개, 계산기 3개. 실제 PostgreSQL 17, Redis 8 Testcontainers와 실제 gRPC transport, 기존 fake YARA engine을 사용했다. Python 실제 YARA 프로세스를 띄운 엔드투엔드 검증은 아니다.
- `git diff --check` 통과. 변경 Java, Gradle, SQL과 새 파일을 포함한 26개 파일의 자격증명, shell 실행, eval 패턴 정적 점검에서 일치 없음. 수동 검토에서 프로덕션 SQL은 바인딩 파라미터를 사용한다.
- JDK 공유 경고, 기존 Gradle deprecation 경고는 남아 있다. 인프라 장애나 자동 skip은 없었다.
- 중간 라이선스 JAR 리소스 구성에서 Gradle 순환 의존성 오류가 한 번 발생했다. `project.files(...)`로 명시해 해결했고 최종 전체 테스트를 다시 통과했다.

## 변경 파일

기존 변경:

- `api-server/build.gradle`, `api-server/settings.gradle`
- `api-server/src/main/java/com/teecherteamc/platform/audit/domain/AuditAction.java`
- `api-server/src/main/java/com/teecherteamc/platform/audit/domain/AuditTargetType.java`
- `api-server/src/main/java/com/teecherteamc/platform/verdict/application/SubmitFileUseCase.java`
- `api-server/src/main/java/com/teecherteamc/platform/verdict/package-info.java`
- `api-server/src/test/java/com/teecherteamc/platform/SchemaMigrationTest.java`

새 파일, 아래 Java 경로의 기준은 `api-server/src/main/java/com/teecherteamc/platform/verdict/`:

- `application/RecordFileFingerprint.java`, `application/ImportFileFingerprint.java`
- `domain/FileFingerprintStore.java`, `domain/FingerprintCalculation.java`, `domain/TlshCalculationPort.java`
- `infrastructure/JdbcFileFingerprintStore.java`, `infrastructure/OfficialTlshCalculator.java`
- `interfaces/FingerprintImportController.java`
- `api-server/src/main/resources/db/migration/V6__file_fingerprints.sql`
- `api-server/src/test/java/com/teecherteamc/platform/TlshFingerprintGrpcIntegrationTest.java`
- `api-server/src/test/java/com/teecherteamc/platform/FingerprintImportRestIntegrationTest.java`
- `api-server/src/test/java/com/teecherteamc/platform/verdict/infrastructure/OfficialTlshCalculatorTest.java`
- `api-server/src/test/resources/tlsh/reference.txt`
- `api-server/third-party/tlsh/`, build 파일, README, LICENSE, NOTICE, 공식 소스 여섯 개
- `docs/tlsh-pr1-report.md`

## 남은 결정, 제안

- Java API 구현을 유지한다. MalwareBazaar에서는 원본 악성 파일 다운로드 대신 SHA256/TLSH 메타데이터를 활용하는 방향이며, 인증된 외부 API 실제 연결은 아직 검증하지 않았다.
- 독립 코드 리뷰 통과: 보안 또는 논리 차단 사항 없음. 리뷰어가 수정/미추적 파일, 공식 벤더 소스와 라이선스, C++ 기준 지문을 확인했다. 전체 테스트 재실행이 아니라 기존 JUnit XML 확인임을 구분한다. 비차단 권고: 가져오기와 업로드/권한/블랙리스트 변경의 동시성 테스트 추가.
- PR2 거리 판정, SIMILARITY, 경고, revision 캐시 무효화와 PR3 콘솔 변경은 없다.
- 제안 커밋 제목, 사용자 승인 전 커밋 금지: `feat: 업로드 파일의 TLSH 지문과 악성 기준 샘플을 준비한다`
