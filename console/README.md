# console

관리 콘솔 (React + Vite + TypeScript). Vercel에 배포한다 — Vercel 프로젝트의 Root Directory를 `console/`로 둔다.

API 서버와는 REST + SSE로 통신한다. 에이전트의 gRPC 채널과는 별개다.

## 실행

레포 루트에서 전체 개발 환경을 실행한다.

```bash
make dev
```

관리 콘솔은 기본적으로 아래 주소에서 확인할 수 있다.

```text
http://localhost:5173
```

환경변수 파일은 예시 파일을 복사해서 사용한다.

```bash
cp console/.env.example console/.env
```

현재 사용 가능한 환경변수:

```env
VITE_API_BASE_URL=http://localhost:8080
```

## 검증

레포 루트에서 콘솔 lint와 production build를 한 번에 검증한다.

```bash
make test-console
```

콘솔 디렉터리에서 개별 실행도 가능하다.

```bash
npm run lint
npm run build
```
