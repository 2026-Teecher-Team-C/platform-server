# 바이패스 도메인 등록, 비활성화 (C3)

## 범위와 코드 흐름

- 기존 `PoliciesPage.tsx`의 검색, 카테고리, 적용 여부, 20/50/100건 페이지 크기와 목록을 유지한다.
- 등록 폼 초안은 검색 폼 초안, 확정된 `query`와 분리한다. `lib/bypass.ts`에서 서버 `BypassHost.of`, `BypassCategory.from`, 사유 필수 규칙을 검사한 후 `lib/api/policies.ts`의 domain client로 POST한다.
- 등록 성공 때 등록 입력, 검색어와 카테고리 필터를 초기화하고 적용 목록 첫 페이지로 이동한다. 페이지 크기는 유지하며 실패 때는 초안과 기존 조회 조건을 유지한다. 비활성화는 기존 조회 조건을 유지한다. 재등록은 최초 등록 시각을 보존하므로 첫 행을 보장하지 않는다.
- 등록 도메인과 검색어의 ×는 해당 입력만 비우고 포커스를 돌려준다. 폼 제출/조회는 하지 않으며 다른 초안은 유지한다. 빈 값과 쓰기 중에는 지우기를 막는다.
- 적용 행의 비활성화 버튼은 대상 host, category와 검사 대상 복귀 경고를 보여준다. host를 정확히 다시 입력해야 DELETE할 수 있다. 미적용 행에는 이 버튼을 제공하지 않는다.
- React `busy`로 조작을 잠그고 동기 `writeLock` ref로 같은 렌더 사이의 중복 submit도 막는다. 두 쓰기 작업은 같은 잠금을 공유한다. 입력, 대상, 오류는 실패 때 유지하고 관련 작업의 성공이나 명시적인 취소 때만 정리한다.
- 성공 후 서버를 다시 읽고, 목록이 줄어 요청 페이지가 범위를 벗어나면 `totalPages - 1`로 제한해 다시 조회한다. 빈 중간 응답으로 기존 결과를 덮어쓰지 않는다.
- GET effect의 `active`와 쓰기 요청의 `lifetime` 세대로 unmount 이후의 성공, 실패 콜백을 무시한다. 상태 변경, 후속 GET을 발생시키지 않는다.
- 마지막 성공 결과, 그 결과의 페이지 정보는 재조회와 조회 실패에도 남긴다. 현재 요청의 200ms 지연 진행 표시, 오류 재시도도 유지한다.
- 통신 오류나 5xx로 쓰기 결과를 알 수 없으면 성공한 GET 전까지 두 쓰기를 막는다. 취소하거나 재조회마저 실패해도 잠금과 등록 초안을 유지한다. 성공 알림은 우측 하단에서 3초 뒤 사라지며 직접 닫을 수 있다.
- 기존 공통 `apiRequest`와 `VITE_API_MODE` 전환을 그대로 사용한다. backend, 인증, 다른 관리 화면은 변경하지 않았다.

## 확인한 실제 서버 계약 (소스 기준)

근거: `policy/interfaces/BypassDomainController.java`, `CreateBypassDomainRequest.java`, `BypassDomainItem.java`, `application/AddBypassDomainUseCase.java`, `RemoveBypassDomainUseCase.java`, `domain/BypassHost.java`, `BypassCategory.java`, `BypassDomain.java`.

- `GET /api/admin/bypass-domains`: `host`, `category`, `isActive`, 0 기반 `page`, `size`. 응답은 `content/page/size/totalElements/totalPages`. `isActive` 생략은 적용만, `false`는 미적용만이며 전체가 아니다. 기존 host 부분 검색과 최신 등록순을 유지한다.
- `POST /api/admin/bypass-domains`: JSON `{ host, category, reason }`. 신규와 재등록 모두 **201**, 항목 `{ bypassId, host, category, reason, isActive, createdAt }`를 반환한다. `updatedAt` 응답은 없다.
- host는 앞뒤 Java trim 공백을 없애고 소문자로 변환, 끝의 점 하나를 없앤다. 전체 253자 이하, 각 라벨 63자 이하, 점 하나 이상, 영문 소문자/숫자/하이픈이며 라벨 양 끝 하이픈 금지, 마지막 라벨에는 영문자가 있어야 한다. 와일드카드, URL 스킴/경로/쿼리/fragment, 포트, 계정 정보, IP 주소는 거절한다. 임의의 URL 보정, IDN 변환은 하지 않는다.
- category는 정확히 `PINNED` 또는 `SECURITY_UPDATE`. 각각 인증서 피닝, 보안 업데이트를 위한 검사 예외 분류다. reason은 빈 값 불가, 원문 저장, 별도의 최대 길이 규칙은 없다.
- 이미 적용 중인 host는 **409 CONFLICT**, 잘못된 입력은 **400 INVALID_INPUT**. 미적용 host를 POST하면 기존 ID와 최초 `createdAt`을 유지하면서 적용 상태, category, reason, 등록자를 이번 요청으로 갱신한다.
- `DELETE /api/admin/bypass-domains/{bypassId}`: 본문 없음, 성공은 **204**. 영구 삭제가 아니라 비활성화다. category, reason, 등록자는 유지되고 활성 정책 목록에서 빠져 다시 검사 대상이 된다. 존재하지 않거나 이미 미적용이면 **404 NOT_FOUND**, 잘못된 UUID는 **400 INVALID_INPUT**.
- 컨트롤러는 인증된 관리자 principal을 사용하고 쓰기는 같은 트랜잭션에 감사 로그를 기록한다. 별도의 ADMIN 역할 검사를 추가하지 않는다. 프런트에도 임의의 역할 정책을 넣지 않았다. 이는 코드 계약 확인이며 실제 인증 검증은 아니다.

## 자동 검증

등록 검증, domain client와 MSW 계약, mounted React 동작을 추가했다. 새 동작은 RED 실행 후 구현하고 GREEN을 확인했다.

- `src/lib/bypass.test.ts`: 24개, 정규화, 금지 입력, category, reason, Java 공백 규칙.
- `src/mocks/handlers/policies.test.ts`: 12개, 기존 목록 4개와 쓰기 계약 8개. POST/DELETE, 201/204/400/404/409, 동일 행 재등록, 이력 보존. HTTP에서 없는 `randomUUID` 대신 `getRandomValues`로 UUID v4를 생성하고 등록/비활성화가 되는 회귀도 확인한다. 인증 helper는 테스트에서 대체하며 인증 검증으로 간주하지 않는다. MSW 3의 `onUnhandledFrame: 'error'`로 미등록 요청의 실제 네트워크 우회를 막는다.
- `src/pages/PoliciesPage.interaction.test.tsx`: 19개. 등록 후 검색 조건/초안 초기화와 첫 페이지 복귀/크기 유지, 두 입력의 개별 지우기/포커스/요청 없음, 기존 오류/잠금/이탈/페이지 보정/알림 회귀를 검증한다.
- 최종 실행: `npm run lint` 경고 없이 성공, `npm run build` 성공, `npm test` 18개 파일 154개 테스트 전부 통과, `git diff --check` 통과.
- 기존 서버 `BypassDomainListRestIntegrationTest` 10개, `BypassDomainRegisterRestIntegrationTest` 9개, `BypassDomainRemoveRestIntegrationTest` 8개, 총 27개 통과. 새로운 Java 테스트를 작성한 것은 아니다.
- 최초 독립 리뷰에서 보안/논리 차단 이슈는 없었다. 제안된 처리 결과 불명 후 GET 실패 회귀 테스트를 반영했다. 후속 통합 mock 화면에서 HTTP의 randomUUID 부재를 발견해 RED→GREEN으로 수정했고 실제 HTTP 화면에서 등록/비활성화와 재조회를 다시 확인했다. 이 후속 수정은 최종 통합 리뷰 대상이다.

## 실제 API와 화면 검증

- 화면 `http://100.70.241.6:5182/policies`, 실제 API `http://100.70.241.6:18084`. mock을 끄고 인증은 비활성화했다. 기존 PC/격리 데이터와 5180/5181 서비스는 유지했다.
- 테스트 DB에 `c3-qa-*.example.com` 합성 도메인 21개를 등록해 시작했다. 화면에서 신규 등록과 비활성화를 검증한 최종 상태는 적용 20개, 미적용 3개, 전체 23개다. 부모가 API를 다시 읽어 개수와 ID 중복 없음을 확인했다. 실제 PC 트래픽에서 수집한 도메인이 아니다.
- 실제 POST/DELETE와 후속 GET으로 정규화, 분류/사유 변경, 재등록의 ID/최초 시각 보존, 409/404와 초안 유지, 중복 제출 1회, 확인 입력과 취소, 두 분류/미적용/20/50/100 크기를 확인했다.
- 20→1→20 페이지 이동과 마지막 페이지 1건 해제 후 1페이지 보정, 실제 응답 지연 후 경로 이탈/복귀, 불명확한 POST와 GET 실패 뒤 잠금 유지/GET 성공 해제를 확인했다. 통신 오류는 실제 서버 장애가 아니라 실제 fetch 응답을 의도적으로 유실/차단한 검증이다.
- 390px/1440px 가로 넘침 없음, 표 내부 스크롤, 우하단 고정 성공 알림과 직접 닫기/약 3초 자동 종료를 확인했다. 증거는 scratch `c3-bypass-qa/qa-outcomes.json`과 같은 폴더의 요청 로그/스크린샷에 보관했다.
- 실제 인증, Agent 정책 전파와 파일 검사는 이 검증에 포함하지 않았다. 인증과 전체 화면 통합은 C4에서 별도로 확인한다.

커밋, push, PR, Kanban 전환은 하지 않는다. C4는 별도 범위다.
