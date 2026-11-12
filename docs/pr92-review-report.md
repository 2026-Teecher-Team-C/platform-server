# PR92 리뷰 수정 검증 보고서

## 상태와 실제 이력

- 리뷰 원본: whgkdwnd, review 5855937833, 13개 표 항목 전체를 원문에서 파싱한 matrix 참조.
- NEW 작업트리만 수정: `/srv/project/T-teamc/platform-server-pr92-review`, `fix/pr92-review`.
- 실제 rebase 완료 HEAD `4fb8980b70efe6ca89b421d22f9624e49c476bc0`, base `3eed665fa634b211a7494c77b9922500e43bb43a` ancestor 검증. replay: a9ce4ae, b0d362e, b18fd03, 4fb8980. 최신 develop #90/#91 API/대시보드 ADMIN 확인 제한 포함.
- 이 보고서의 리뷰 수정은 위 HEAD 이후 **미커밋 작업트리**. 새 fix 커밋, push, PR/API 쓰기, 리뷰 resolve, board 변경 없음. 공개 PR head는 여전히 `863235678ec604ef3e40383aee7977780b8a4d25`.
- 로컬 13/13 검증, 게시/독립 리뷰 완료라는 뜻은 아님. 부모가 manifest 기준 리뷰, 논리 커밋, remote lease 재확인, 본문 갱신 담당.

## 13개 항목

| # | 수정 종류 | 실제 구현/판단 | 코드/테스트 근거 |
|---|---|---|---|
| 1 | new | actual rebase 4fb8980 onto latest 3eed665, TLSH V8-V11 + forward V12, deployed V1-V7 byte identical | DevelopMigrationUpgradeTest |
| 2 | new | eligible-only corpus revisions, corpus-only stale recompare reuses successful CLEAN YARA without new analysis/count, rules staleness preserved | SimilarityCorpusReviewIntegrationTest.unrelated_list_changes_leave_corpus_token_unchanged, TlshSimilarityGrpcIntegrationTest.corpus_only_change_reuses_clean_engine_analysis |
| 3 | safer-alternative | ordinary uploads no global exclusive lock; SHA eligibility gate covers absent fingerprint/baseline INSERT race, API list writes gate before row writes | SimilarityCorpusReviewIntegrationTest.ordinary_fingerprint_upload_does_not_wait_for_global_shared_reader, SimilarityCorpusReviewIntegrationTest.new_baseline_insert_waits_for_uncommitted_fingerprint_then_revises_corpus |
| 4 | reused-resolution | retain latest ADMIN policy and USER role, actual sample ADMIN200 ANALYST403 USER403 | SampleDetailRestIntegrationTest, RolePermissionRestIntegrationTest |
| 5 | new | CLAUDE and current design describe Java verified-byte fingerprint and post-YARA comparison; no short-file policy change | docs/tlsh-design.md, CLAUDE.md, api-server/third-party/tlsh/README.md |
| 6 | reused-resolution | ANALYST plain SHA and ADMIN notice, no active href; ADMIN returnTo link and sample page verified on actual API | EventDetailPage.interaction.test.tsx, SimilarityEvidence.test.tsx |
| 7 | base-preserved | retain roles/allowedMenus and ADMIN enrollment, latest dashboard ADMIN acknowledge restriction; replay VIEWER API type corrected to USER | Sidebar.test.tsx, roles.test.ts, DashboardPage.test.tsx, enrollment.test.ts |
| 8 | new | only refresh credential401/403 invalidates session, network/5xx preserve tokens; old success/error and logout races protected | client.test.ts, session.real.test.ts |
| 9 | new | nonblocking pg_try shared lock returns UNKNOWN without aborted transaction or cached ALLOW | SimilarityCorpusReviewIntegrationTest.check_hash_returns_unknown_with_bounded_lock_wait |
| 10 | safer-alternative | forward V12 maps SIMILARITY BLOCKED to operational MEDIUM(2), own reason type and no invented YARA rule; V7 immutable | DevelopMigrationUpgradeTest, TlshSimilarityGrpcIntegrationTest.padding_variant_blocks_after_clean_engine_and_preserves_original_analysis |
| 11 | safer-alternative | complete token snapshots, generation nonce protects rollback reuse, synchronized bounded LRU 4 tokens/8192 retained; oversized corpus fully streamed, cold miss remains under lock for consistency | SimilarityCorpusReviewIntegrationTest.unchanged_corpus_parses_baseline_only_once_across_comparisons, ParsedSimilarityCorpusTest, SimilarityCorpusReviewIntegrationTest.thousand_candidate_db_probe_measures_complete_comparison_and_warm_reuse |
| 12 | new | SimilarityDecisions raw SQL removed behind SimilarityDecisionStore/Jdbc adapter, exact lookups reuse existing ports, distance policy remains application | SimilarityArchitectureContractTest.application_uses_ports_not_sql |
| 13 | new | single DB similarity_lock_key function used by Java and SQL triggers, numeric contract and unknown-scope fail test | SimilarityArchitectureContractTest.advisory_keys_have_one_sql_source_not_repeated_java_numbers, SimilarityCorpusReviewIntegrationTest.shared_lock_key_contract_matches_sql_and_rejects_unknown_scope |

## 새 실행 결과

- 서버 `./gradlew test check bootJar --rerun-tasks`: 594 tests, failures=0, errors=0, skipped=0, BUILD SUCCESSFUL. XML 자동 합산, 이전 실패 로그는 보존.
- console `npm ci`, lint, build 성공. 기본 전체 suite 477 passed, 0 failed, 4 opt-in real tests skipped.
- 별도 실제 인증 enabled API/Redis 대상 opt-in suite 4 passed, 0 failed. 로그인, 실제 access 만료+60초 오차, concurrent refresh single-flight, 회전 토큰 재사용 거부, logout/relogin late response 폐기, refresh503/network 고의 fault injection 후 토큰 보존/복구 검증. 마지막 둘은 실제 서버 장애가 아니라 fetch 갱신 호출만 차단한 시뮬레이션.
- 새 behavior의 RED/GREEN 로그: corpus, recompare(+analysis_count), lockscope, check-lock, ports, parsed, keys, view, auth(+stale error). 구조 변경은 소스 계약 검사, 성능 변경은 실제 DB/공식 파서 검사.
- padding: 실제 무해 reference 바이트+64 padding, measured distance 17, 최종 SIMILARITY BLOCK, 원본 analysis CLEAN, 실제 이벤트 FK/격리 조정 테스트 통과. engine/storage는 테스트 대역, 실제 Python-YARA/S3 전체 E2E 주장은 아님.
- 기존 ownership, exact whitelist, YARA 악성 우선, rules stale, S3 늦은 완료/ReportEvent, 요청 간 과거 스냅샷 보존 테스트 모두 전체 suite 포함.
- DB upgrade: V5 기존 VIEWER 데이터 -> 최신 develop V7 USER/view -> V8~V12, repair/ignore 없이 validate, V1~V7 파일/기록 체크섬 보존, 12개 migration만 bootJar에 포함.

## 측정과 안전한 대안

- real Testcontainers DB 1000 후보 probe: cold 21.836ms, warm 1.053ms (동일 machine의 단일 테스트 실행, DB/거리 전체 포함). throughput/대규모 실데이터 정확도 검증은 아님.
- 동일 토큰 기준 지문 파싱 2회 비교 동안 1회임을 공식 static parser 호출 수로 검증. 16개 concurrent consumer는 하나의 완성된 load를 공유. 10000 후보 초과 예산 시험은 메모리 단위 테스트이며 실제 DB 10000 벤치마크 아님, 모든 후보 방문과 비캐시 경로 확인.
- 첫 cold miss는 락 안에서 일관된 목록을 읽고 파싱. 전체 파싱을 락 밖으로 옮겨 stale/부분 corpus를 게시하는 변경은 하지 않음. 캐시 예산과 후보 수 제한을 혼동하지 않음.
- SHA eligibility gate로 새 기준과 지문 INSERT 경쟁을 막고 일반 업로드의 전역 배타 락 제거. 목록의 정확 예외 변경은 여전히 전역 경계 참여, 관리 API는 행 쓰기 전 gate->corpus. 임의 직접 SQL 대량 쓰기의 잠금 순서/재시도는 별도 운영 관리 범위.
- dashboard SIMILARITY는 확정 악성/높은 YARA 위험도와 구별해 MEDIUM(2) 운영 분류. 사용자 정책의 거리/BLOCK/WARN 경계는 바꾸지 않음.

## 실제 API/화면 QA

- 전용 `pr92-review-postgres` 15489, `pr92-review-redis` 16389, API 127.0.0.1:18089, gRPC 127.0.0.1:19089. 기존 18086/18088/5191 등 미변경.
- 실제 frontend `http://100.70.241.6:5192` (같은 호스트 localhost5192), proxy -> 전용 loopback API. auth enabled, mock/service worker 없음. private proxy login200/healthUP 검증.
- 실제 REST sample ADMIN200, ANALYST403, USER403. ADMIN 실제 페이지 링크/returnTo, ANALYST plain SHA+ADMIN 안내/no href 브라우저 3개 확인 PASS.
- 화면 행은 테스트 DB에 직접 대입한 메타데이터, 실제 탐지 아님. 새 같은 SHA assessment를 넣어도 원래 이벤트 similarity snapshot 동일함을 REST 재조회로 확인.
- 로그인 폼 수동 입력/실제 Agent download/Python-YARA/S3 연결은 미검증. 미사용 engine 주소와 무효 AWS 자격으로 외부 upload 호출 없이 read-only QA 진행.
- frontend 첫 background 실행은 cwd drift로 실패, explicit npm --prefix로 복구. 최초 fixture transaction은 imported_by 누락으로 rollback, 올바른 ADMIN FK로 재시드 후 exact target 재조회. 실패를 PASS로 덮어쓰지 않음.

## 보존/보안과 게시 전 게이트

- 보호 3개 원본 tree의 HEAD/status/hash 전부 동일 (`--untracked-files=all`로 비교), 98+121개 보호 파일 해시 동일. no other worktree edits.
- tracked base-to-working-tree+untracked 텍스트 전체 secret/injection scan, 후보 3개는 기존 테스트/mock 합성 토큰뿐, 실제 자격/비밀 파일은 profile scratch 0600, repo에 없음.
- pinned 공식 TLSH 6개 Java/2개 LICENSE/NOTICE 원격 바이트 동일 검증. 전체 diff whitespace 경고는 그 고정 원본에만 존재, 정확 8개 파일 예외를 뺀 검사 PASS, vendor bytes 변경 안 함.
- parent independent review/논리 커밋/게시가 남음. remote ref가 기대와 달라지면 게시 중단, 작업 보존. 새 PR/develop push/merge/review resolve 금지.
- MalwareBazaar 데이터 공급, 사후 소급 알림 연결, CheckHash-only WARN event correlation은 기존 범위 제한으로 남음. 선택사항 지문 CRUD/짧은 파일 WARN 전환/추가 parser400 보강은 확장하지 않음.

증거 디렉터리: `/home/hajh1113/.hermes/profiles/team-c/cache/scratch/pr92-review/`. 자세한 파일별 proof와 initial/final 상태는 review-matrix JSON, 변경 allowlists/manifests는 parent-handoff JSON 참조.
