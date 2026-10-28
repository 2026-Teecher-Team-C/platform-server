import itertools
import logging
import os
import tempfile
from concurrent import futures
from pathlib import Path

import grpc
import yara

from detection_engine.analyzer import ENGINE_VERSION, Analyzer
from teecher.engine.v1 import engine_pb2, engine_pb2_grpc

logger = logging.getLogger(__name__)

# 한 파일을 디스크에 받는 상한. 넘으면 분석하지 않고 UNSUPPORTED로 돌려준다.
MAX_FILE_BYTES = int(os.environ.get("ENGINE_MAX_FILE_BYTES", str(256 * 1024 * 1024)))


class DetectionEngineServicer(engine_pb2_grpc.DetectionEngineServicer):
    def __init__(self, analyzer: Analyzer, spool_dir: Path | None = None):
        self._analyzer = analyzer
        self._spool_dir = spool_dir

    def CompileRules(self, request, context):
        try:
            rules = Analyzer.compile_source(request.source)
        except (yara.Error, ValueError) as error:
            return engine_pb2.CompileRulesResponse(ok=False, error_message=str(error))
        return engine_pb2.CompileRulesResponse(
            ok=True, rules=[engine_pb2.CompiledRule(name=name, severity=severity) for name, severity in rules]
        )

    def LoadRuleset(self, request, context):
        try:
            self._analyzer.load(
                request.rulesets_version, request.ruleset_key, request.source, request.disabled_rule_names
            )
        except (yara.Error, ValueError) as error:
            context.abort(grpc.StatusCode.INVALID_ARGUMENT, str(error))
        return engine_pb2.LoadRulesetResponse()

    def Analyze(self, request_iterator, context):
        # 청크를 읽기 전에 첫 메시지(metadata)의 룰셋 키부터 맞춰 본다. 비어 있으면 검사하지 않는다.
        first = next(request_iterator, None)
        if first is not None:
            if first.WhichOneof("payload") == "metadata":
                expected = first.metadata.ruleset_key
                if expected and expected != self._analyzer.key:
                    context.abort(grpc.StatusCode.FAILED_PRECONDITION, "ruleset mismatch")
            request_iterator = itertools.chain([first], request_iterator)

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
                        version, key = self._analyzer.loaded()
                        return engine_pb2.AnalyzeResponse(
                            status=engine_pb2.ANALYSIS_STATUS_UNSUPPORTED,
                            verdict=engine_pb2.ENGINE_VERDICT_UNKNOWN,
                            engine_version=ENGINE_VERSION,
                            rulesets_version=version,
                            ruleset_key=key,
                            error_message=f"file exceeds {MAX_FILE_BYTES} bytes",
                        )
                    spool.write(request.chunk)

            result = self._analyzer.analyze_file(path)
            # 버전과 키는 판정에 쓴 스냅샷에서 가져온다. 분석 뒤에 analyzer를 다시 읽으면 그 사이 적재된
            # 새 룰셋의 값이 섞여, API 서버가 옛 룰로 낸 판정을 새 룰셋 판정으로 저장할 수 있다.
            return engine_pb2.AnalyzeResponse(
                status=engine_pb2.ANALYSIS_STATUS_SUCCESS,
                verdict=engine_pb2.ENGINE_VERDICT_MALICIOUS if result.malicious else engine_pb2.ENGINE_VERDICT_CLEAN,
                engine_version=ENGINE_VERSION,
                rulesets_version=result.rulesets_version,
                ruleset_key=result.ruleset_key,
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
