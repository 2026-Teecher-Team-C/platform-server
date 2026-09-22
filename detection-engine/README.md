# detection-engine

Python 3.12 · gRPC(50051) · yara-python

공격자가 만든 파일을 파싱하는 가장 위험한 컨테이너다. `engine-net`(internal)에만 붙어 있어 API 서버
외에는 어디에도 연결할 수 없고, 컨테이너는 읽기 전용 · 메모리 1GB · CPU 1개로 제한된다.

현재 단계: YARA 매칭만 한다(`rules/eicar.yar`). 매직바이트 → PE 파싱 → 섹션 엔트로피, 파일별 워커
프로세스 격리(`setrlimit` + 타임아웃)는 이후 단계에서 붙는다.

```bash
make test-engine        # 레포 루트에서 (Docker)
# 또는 로컬 uv
./scripts/gen_proto.sh && uv run --group dev pytest
```
