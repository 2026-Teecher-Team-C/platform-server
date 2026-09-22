# console

관리 콘솔 (React + Vite + TypeScript). Vercel에 배포한다 — Vercel 프로젝트의 Root Directory를 `console/`로 둔다.

API 서버와는 REST + SSE로 통신한다. 에이전트의 gRPC 채널과는 별개다.

```bash
make dev            # 레포 루트에서 — http://localhost:5173
npm run lint
npm run build
```
