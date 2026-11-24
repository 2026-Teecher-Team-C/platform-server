# TLSH 보조 판단 설계 (PR92 리뷰 수정본)

## 승인된 배치와 우선순위

Python 엔진의 YARA/PE 분석은 유지한다. Java API는 업로드 SHA256 검증 뒤 공식 Java TLSH로 지문을 계산, 저장한다. 지문 계산은 YARA 실행 여부와 별개이며, 거리 정책은 기존 검사에서 허용된 파일에만 추가한다. 정확 SHA 화이트리스트만 허용 예외이고, SHA/YARA 악성 차단을 TLSH가 해제하지 않는다. 계산 불가와 DB/엔진 장애를 구분, DB 오류를 빈 corpus/ALLOW로 바꾸지 않는다.

활성 SHA256 블랙리스트, CALCULATED 지문, 현재 유효한 정확 화이트리스트 제외 조건을 모두 만족하는 기준만 비교한다. 거리 <=30은 SIMILARITY 차단, 31~70은 기존 허용+WARN, >70은 기존 판단 유지. 공식 force 최소 50바이트를 유지하며 <256바이트 차단을 WARN으로 낮추는 정책은 승인되지 않아 적용하지 않는다.

## 캐시와 원본 분석

- `file_verdicts.is_stale`은 YARA 룰 변경을 포함한다. stale이면 엔진 재검사, corpus 토큰만 달라졌다면 최근 SUCCESS/CLEAN 원본 분석을 재사용해 거리만 재평가한다. 분석 횟수/시각을 새 YARA 검사처럼 늘리지 않는다.
- 룰 변경의 stale 쓰기와 보조 정책 저장은 활성 룰 키 공유 락으로 직렬화한다. 실패/악성 YARA 결과는 재사용 CLEAN 경로로 들어가지 않는다.
- 토큰은 후보에 영향 있는 쓰기의 revision, 롤백 후 revision 재사용을 막는 UUID generation, 관련 화이트리스트의 자연 만료 수, 정책 버전을 포함한다. 무관한 SHA 추가/설명 수정은 revision을 올리지 않는다.
- 파싱 캐시는 토큰별 완성된 불변 목록만 게시, synchronized single-flight/LRU, 최대 4개 토큰과 합계 8192개 후보 보관. 구축 중에도 수집 목록을 8192개로 제한, 넘으면 저장하지 않고 전체 cursor를 끝까지 비교한다. 이는 후보 수 제한이 아니다. 기존 보관 목록+구축 목록은 최대 16384개, 그 밖에는 cursor 한 묶음과 현재 후보 정도만 추가된다.
- 첫 miss는 일관된 후보를 얻기 위해 corpus 공유 락 안에서 전체 cursor를 파싱한다. 락을 풀고 비일관된 목록을 게시하는 최적화는 하지 않는다. warm 비교는 기준 파싱을 재사용하지만 거리 계산 자체는 여전히 O(N), 프로덕션 대규모 처리량 보장은 아니다.

## DB/포트와 잠금 경계

`SimilarityDecisions`는 정책/기존 WhitelistLookupPort, BlacklistLookupPort를 사용한다. raw SQL, cursor, 근거 저장은 `SimilarityDecisionStore`와 `JdbcSimilarityDecisionStore`로 분리한다. Java/SQL 트리거 모두 DB의 `similarity_lock_key(scope)`를 호출, 숫자 원본은 SQL 함수 한 곳에만 둔다. 이름 오타는 예외로 거부한다.

지원되는 목록 관리/API import는 행 쓰기 전에 SHA eligibility gate -> corpus 순서, 일반 fingerprint는 eligibility gate -> file 순서만 사용한다. 활성 기준/ADMIN_IMPORT fingerprint만 corpus 배타 락을 추가한다. gate는 아직 지문/블랙리스트 행이 없는 INSERT 경쟁도 직렬화한다. import는 corpus 획득 뒤 baseline 행 SHARE를 중첩하지 않는다. CheckHash는 `pg_try_advisory_xact_lock_shared` 실패 시 즉시 UNKNOWN, 기존 ALLOW 캐시를 읽지 않고 트랜잭션 오류도 만들지 않는다.

SubmitFile은 짧은 트랜잭션의 corpus -> file -> 활성 룰 경계를 유지, 엔진/S3 I/O는 DB 락 밖에 둔다. 이벤트 소유권 예약과 늦은 격리 완료/ReportEvent는 같은 event 락을 사용한다. 일반 직접 SQL 대량 관리 작업의 임의 잠금 순서/DB deadlock retry는 애플리케이션 API의 성능 보장 범위가 아니며, 운영 쓰기는 관리 포트를 사용해야 한다.

## 이력과 화면

이벤트는 당시의 assessment FK만 조회, 최신 SHA 평가로 과거를 덮지 않는다. 샘플 상세는 현재 메타데이터 조회이며 ADMIN 전용이다. ANALYST에게는 SHA와 ADMIN 안내만 표시한다. 최신 develop의 USER/RBAC, 사이드바 allowedMenus, 대시보드 ADMIN 확인 버튼 제한을 유지한다.

배포된 V1~V7은 변경하지 않는다. 미배포 TLSH는 V8~V11, V12로 기존 dashboard view를 전방 갱신한다. SIMILARITY/BLOCKED는 운영 분류 MEDIUM(2), reason_type SIMILARITY, YARA 룰 이름 없음. 이는 확정 악성/높은 심각도라는 주장이 아니며 기존 높은 심각도 확인 큐에 자동 승격하지 않는다.

콘솔 refresh는 자격 거절 401/403에만 세션 삭제, 네트워크/5xx는 요청 오류만 전달한다. 단일 flight, 재시도 한 번, 로그아웃/새 세션 뒤 늦은 성공/실패 AbortError 처리와 새 refresh 폐기를 유지한다.

## 유사 파일 검색 (IMP-12 구성요소)

`FindSimilarFiles`(verdict)는 기준 TLSH, 또는 sha256의 계산된 지문과의 거리가 임계값 이하인 `file_fingerprints`를 가까운 순으로 돌려준다. 판정(CheckHash/SubmitFile)과 별개이며 소급 조회가 "비슷한 파일"의 SHA 집합을 얻는 용도다. 이 집합으로 기기를 찾고 알리는 일은 호출하는 쪽(IMP-10)의 몫이다.

- 거리는 Java(`Tlsh.totalDiff`)에서 계산하고 DB는 행만 커서(fetch 256)로 넘긴다. 전체를 읽는 O(N) 스캔이며 캐시가 없다. `ParsedSimilarityCorpus`는 쓰지 않는다. `visit` 전체가 synchronized라 긴 스캔이 판정 비교를 막고, 토큰이 일반 지문 INSERT에 바뀌지 않아 전체 테이블 캐시의 키가 될 수 없다.
- advisory 잠금을 잡지 않는다. readOnly 트랜잭션의 단일 SELECT라 문장 시작 시점 스냅샷을 본다. 호출자는 블랙리스트 등록 같은 쓰기 트랜잭션 안에서 부르지 않고 커밋 뒤에 부른다. 등록 트랜잭션은 코퍼스 배타 락을 커밋까지 쥐고 있고, CheckHash는 그 락을 못 얻으면 UNKNOWN으로 내려가기 때문이다. 일괄 가져오기에서 건마다 부르지 않는다.
- 입력은 sha256과 TLSH 둘 다다. 스캔 대상 출처는 인자로 고르며 기본은 기기가 받은 파일(`VERIFIED_UPLOAD`)이다. 기준 자신만 결과에서 빼고, 화이트리스트·블랙리스트 여부는 거르지 않는다(소급 알림 정책은 호출자가 정한다).
- 결과는 상태(OK, NO_REFERENCE_FINGERPRINT, INVALID_TLSH), 거리 오름차순(같으면 SHA 오름차순) 최대 limit개(상한 1000), 더 있으면 truncated, 스캔한 행 수다. 요청 가능한 거리는 100까지다. 지문이 없는 SHA는 빈 결과가 아니라 NO_REFERENCE_FINGERPRINT다.
- 한계: 지문은 서버가 검증한 업로드와 관리자 가져오기만 저장된다. 업로드 없이 통과한 다운로드와 에이전트가 주장한 TLSH는 저장되지 않아 찾지 못한다. 스캔 비용은 측정하지 않았다(스캔 행 수와 시간은 서비스 로그에 남는다). 느리면 길이 항 사전 필터(새 마이그레이션 필요)를 검토한다. REST 엔드포인트는 두지 않았다.

## 명시적 비범위

MalwareBazaar 자동 공급, 외부 악성 바이너리 다운로드, 사후 소급 경고 알림 연동(유사 파일 검색 구성요소만 있고 알림 연동은 IMP-10), 지문 조회/삭제 CRUD 추가는 구현하지 않는다. CheckHash-only WARN은 event correlation이 없어 이벤트 근거로 연결하지 않는다. 이 PR의 실제 무해 padding 검증은 real DB + fake engine/storage, 별도 브라우저 QA 행은 수동 대입한 메타데이터다. 실제 Agent/Python-YARA/S3 전체 종단 검증과 로그인 폼의 수동 입력 검증은 별도다.
