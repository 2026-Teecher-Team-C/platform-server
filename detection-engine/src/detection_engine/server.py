import logging
import os
import tempfile
from concurrent import futures
from pathlib import Path

import grpc

from detection_engine.analyzer import ENGINE_VERSION, Analyzer
from teecher.engine.v1 import engine_pb2, engine_pb2_grpc

logger = logging.getLogger(__name__)

# 한 파일을 디스크에 받는 상한. 넘으면 분석하지 않고 UNSUPPORTED로 돌려준다.
MAX_FILE_BYTES = int(os.environ.get("ENGINE_MAX_FILE_BYTES", str(256 * 1024 * 1024)))


class DetectionEngineServicer(engine_pb2_grpc.DetectionEngineServicer):
    def __init__(self, analyzer: Analyzer, spool_dir: Path | None = None):
        self._analyzer = analyzer
        self._spool_dir = spool_dir

    def Analyze(self, request_iterator, context):
        # 청크를 메모리에 모으지 않고 임시 파일에 쓴다. mkstemp는 확장자 없는 무작위 이름에 0600 권한으로
        # 만들므로 실행 비트가 없고, 분석이 끝나면(성공·실패·상한 초과 모두) finally에서 지운다.
        fd, name = tempfile.mkstemp(prefix="analyze-", dir=self._spool_dir)
        path = Path(name)
        try:
            written = 0
            with os.fdopen(fd, "wb") as spool:
                for request in request_iterator:
                    if request.WhichOneof("payload") != "chunk":
                        continue
                    written += len(request.chunk)
                    if written > MAX_FILE_BYTES:
                        return engine_pb2.AnalyzeResponse(
                            status=engine_pb2.ANALYSIS_STATUS_UNSUPPORTED,
                            verdict=engine_pb2.ENGINE_VERDICT_UNKNOWN,
                            engine_version=ENGINE_VERSION,
                            error_message=f"file exceeds {MAX_FILE_BYTES} bytes",
                        )
                    spool.write(request.chunk)

            result = self._analyzer.analyze_file(path)
            return engine_pb2.AnalyzeResponse(
                status=engine_pb2.ANALYSIS_STATUS_SUCCESS,
                verdict=engine_pb2.ENGINE_VERDICT_MALICIOUS if result.malicious else engine_pb2.ENGINE_VERDICT_CLEAN,
                engine_version=ENGINE_VERSION,
                matches=[
                    engine_pb2.RuleMatch(
                        rule_name=hit.rule_name, severity=hit.severity, matched_strings=hit.matched_strings
                    )
                    for hit in result.matches
                ],
                duration_ms=result.duration_ms,
            )
        finally:
            path.unlink(missing_ok=True)


def create_server(analyzer: Analyzer, address: str, spool_dir: Path | None = None) -> tuple[grpc.Server, int]:
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=4))
    engine_pb2_grpc.add_DetectionEngineServicer_to_server(DetectionEngineServicer(analyzer, spool_dir), server)
    port = server.add_insecure_port(address)
    return server, port


def main() -> None:
    logging.basicConfig(level=logging.INFO)
    rules_dir = Path(os.environ.get("ENGINE_RULES_DIR", "rules"))
    spool_dir = Path(os.environ["ENGINE_SPOOL_DIR"]) if "ENGINE_SPOOL_DIR" in os.environ else None
    address = os.environ.get("ENGINE_ADDRESS", "0.0.0.0:50051")
    server, port = create_server(Analyzer(rules_dir), address, spool_dir)
    server.start()
    logger.info("detection engine listening on port %d", port)
    server.wait_for_termination()


if __name__ == "__main__":
    main()
