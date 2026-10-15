# API 컨벤션

api-server의 REST API(`8080`)가 따르는 공통 형식이다.
구현은 `com.teecherteamc.platform.common` 패키지에 있다.

## 1. 공통 에러 응답 형식

모든 에러 응답은 `ErrorResponse` 하나의 형식으로 내려간다.

```json
{
  "timestamp": "2026-10-15T11:30:00Z",
  "status": 400,
  "code": "INVALID_INPUT",
  "message": "입력값이 올바르지 않습니다.",
  "path": "/api/v1/whitelist",
  "fieldErrors": [
    { "field": "reason", "reason": "must not be blank" }
  ]
}
```

| 필드 | 설명 |
|---|---|
| `timestamp` | 에러가 난 시각 (UTC, ISO-8601) |
| `status` | HTTP 상태 코드 |
| `code` | 에러 종류. 프론트는 `message`가 아니라 이 값으로 분기한다 |
| `message` | 사람이 읽는 설명. 문구는 바뀔 수 있으므로 분기에 쓰지 않는다 |
| `path` | 요청 경로 |
| `fieldErrors` | 입력 검증 실패 시 필드별 사유. 그 외에는 빈 배열 |

### 에러 코드

| code | HTTP | 언제 |
|---|---|---|
| `INVALID_INPUT` | 400 | 입력 검증 실패, 읽을 수 없는 요청 본문 |
| `UNAUTHORIZED` | 401 | 로그인이 필요함 |
| `FORBIDDEN` | 403 | 접근 권한 없음 |
| `NOT_FOUND` | 404 | 대상이 없음, 없는 경로 |
| `METHOD_NOT_ALLOWED` | 405 | 허용되지 않는 요청 방식 |
| `CONFLICT` | 409 | 이미 존재하거나 충돌하는 요청 |
| `INTERNAL_ERROR` | 500 | 서버 오류 |

코드를 추가해야 하면 `ErrorCode`에 항목을 넣고 이 표도 같이 고친다.

### 500 응답

예외 메시지나 스택 트레이스는 응답에 싣지 않는다. 응답은 `INTERNAL_ERROR`의 기본 메시지만 내려가고, 상세 내용은 서버 로그에만 남는다.

### 던지는 방법

```java
throw new ApiException(ErrorCode.NOT_FOUND);
throw new ApiException(ErrorCode.CONFLICT, "이미 등록된 해시입니다.");
```

`ApiException`은 interfaces 계층에서 던진다. domain은 이 예외에 의존하지 않는다.
처리는 `GlobalExceptionHandler`가 맡는다.

## 2. 페이지네이션 형식

목록 응답은 `PageResponse`로 내려간다. `page`는 0부터 시작한다.

```json
{
  "content": [ { "...": "..." } ],
  "page": 0,
  "size": 20,
  "totalElements": 135,
  "totalPages": 7
}
```

| 필드 | 설명 |
|---|---|
| `content` | 해당 페이지의 항목 |
| `page` | 현재 페이지 (0부터) |
| `size` | 페이지 크기 |
| `totalElements` | 전체 항목 수 |
| `totalPages` | 전체 페이지 수 |

요청은 쿼리 파라미터 `page`, `size`를 쓴다. 예: `GET /api/v1/blacklist?page=0&size=20`

```java
// 예: 엔티티 Page를 응답 DTO로 바꿔서 내려준다
Page<BlacklistEntry> page = ...;
return PageResponse.from(page, BlacklistEntryView::from);
```

엔티티를 그대로 내리지 않고 변환 함수를 넘겨 응답 DTO로 바꾼다.

## 3. 아직 정하지 않은 것

- 인증 적용: 헬스체크를 제외한 엔드포인트에 인증을 적용하는 방안이다. 기능 개발과 테스트에 지장을 줄 수 있어 문서화만 먼저 하고, 실제 구현은 마무리 단계에서 한다.
- Spring Security의 401/403 응답: 인증 의존성을 추가할 때 같은 `ErrorResponse` 형식으로 맞춘다.
- SSE 인증 방식: `EventSource`는 `Authorization` 헤더를 못 보내므로 쿠키 사용을 우선 검토한다. 적용이 어려우면 쿼리 토큰을 대안으로 쓴다. 구현할 때 확정한다.
- CORS: 현재는 api-server에서 허용 도메인을 설정한다. 인증을 추가할 때 Security 필터 체인과 함께 정리한다.
- `size` 상한: 지금은 제한이 없다.
