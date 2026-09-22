# CLAUDE.md

프록시 기반 악성코드 다운로드 탐지 플랫폼의 서버 레포. 설계 근거는 설계 레포
(2026-Teecher-Team-C/project)에 있다 — 구조를 바꾸기 전에 그쪽 문서를 먼저 확인한다.

## 명령

```bash
make dev | make test | make proto | make down     # 전부 Docker 안에서 실행
```

컴포넌트별: `make test-api` · `make test-engine` · `make test-console`

## 되돌리면 안 되는 결정

- **fail-close 고정.** 검사 서버에 닿지 못하면 다운로드는 차단된다. fail-open, 정책 토글,
  재시도·서킷 브레이커·로컬 캐시 폴백을 만들지 않는다
- **탐지 엔진은 API 서버하고만 통신한다.** `engine-net`(internal)에만 붙어 있고 인터넷·AWS·Slack에
  닿지 않는다. Slack 알림·S3 업로드·DB 기록은 전부 API 서버가 한다. 엔진에 외부 연결을 추가하지 않는다
- **파일 내용 파싱은 탐지 엔진에서만.** API 서버는 받은 바이트를 해석하지 않고 엔진에 넘긴다
- **화이트리스트는 SHA256만.** TLSH는 블랙리스트에만 예약된 값이며 현재 어떤 단계도 계산하지 않는다
- **블룸 필터는 삭제가 불가능하다.** 오탐 복원은 필터를 고치지 않고 화이트리스트(조회 사슬 ①)로 막는다
- **격리 S3 객체**: 키는 `quarantine/YYYY/MM/DD/{uuid}`(확장자 없음), `Content-Type: application/octet-stream`,
  버킷은 Block Public Access. 원본 파일명은 `quarantine_files.original_filename`에만 둔다
- 비대응 범위(QUIC, 피닝 앱, Range 분할, USB, 제로데이, 행위 분석)는 "고치지" 않는다

## api-server (DDD)

- 바운디드 컨텍스트 = `com.teecherteamc.platform.<context>` 패키지. 각 `package-info.java`에 소유 테이블과
  애그리거트 루트가 적혀 있다
- 컨텍스트 안은 `domain` / `application` / `infrastructure` / `interfaces`. `domain`은 Spring·JPA 외의
  프레임워크에 의존하지 않는다. 다른 컨텍스트의 애그리거트를 직접 수정하지 않고 도메인 이벤트로 알린다
- `lookup`은 예외다. 다운로드마다 호출되는 읽기 경로라 애그리거트를 로딩하지 않고 Redis·전용 쿼리로 읽는다
- 스키마는 Flyway가 소유한다(`ddl-auto: validate`). 기존 마이그레이션 파일은 수정하지 않고 `V{n}__...`를 추가한다
- 코드 스타일: 우테코 스타일(4칸, 줄바꿈 8칸, 120자). 테스트 메서드 이름은 한글 가능

## proto

- 원본은 `proto/`. 에이전트 레포가 `proto-v*` 태그로 고정해 쓴다
- 필드 번호 재사용·타입 변경·삭제 금지. 바꾸면 태그를 새로 단다
