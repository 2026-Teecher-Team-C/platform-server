import logging
import os
from concurrent import futures
from pathlib import Path

import grpc

from detection_engine.analyzer import ENGINE_VERSION, Analyzer
from teecher.engine.v1 import engine_pb2, engine_pb2_grpc

logger = logging.getLogger(__name__)

# 한 파일을 메모리에 모으는 상한. 넘으면 분석하지 않고 UNSUPPORTED로 돌려준다.
MAX_FILE_BYTES = int(os.environ.get("ENGINE_MAX_FILE_BYTES", str(256 * 1024 * 1024)))


class DetectionEngineServicer(engine_pb2_grpc.DetectionEngineServicer):
    def __init__(self, analyzer: Analyzer):
        self._analyzer = analyzer

    def Analyze(self, request_iterator, context):
        buffer = bytearray()
        for request in request_iterator:
            if request.WhichOneof("payload") == "chunk":
                buffer.extend(request.chunk)
                if len(buffer) > MAX_FILE_BYTES:
                    return engine_pb2.AnalyzeResponse(
                        status=engine_pb2.ANALYSIS_STATUS_UNSUPPORTED,
                        verdict=engine_pb2.ENGINE_VERDICT_UNKNOWN,
                        engine_version=ENGINE_VERSION,
                        error_message=f"file exceeds {MAX_FILE_BYTES} bytes",
                    )

        result = self._analyzer.analyze(bytes(buffer))
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


def create_server(analyzer: Analyzer, address: str) -> tuple[grpc.Server, int]:
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=4))
    engine_pb2_grpc.add_DetectionEngineServicer_to_server(DetectionEngineServicer(analyzer), server)
    port = server.add_insecure_port(address)
    return server, port


def main() -> None:
    logging.basicConfig(level=logging.INFO)
    rules_dir = Path(os.environ.get("ENGINE_RULES_DIR", "rules"))
    address = os.environ.get("ENGINE_ADDRESS", "0.0.0.0:50051")
    server, port = create_server(Analyzer(rules_dir), address)
    server.start()
    logger.info("detection engine listening on port %d", port)
    server.wait_for_termination()


if __name__ == "__main__":
    main()
