from pathlib import Path

import grpc
import pytest

from detection_engine.analyzer import Analyzer
from detection_engine.server import create_server
from teecher.engine.v1 import engine_pb2, engine_pb2_grpc

RULES_DIR = Path(__file__).resolve().parents[1] / "rules"

# 로컬 백신이 테스트 파일을 격리하지 않도록 EICAR 문자열을 조립해서 쓴다.
EICAR = ("X5O!P%@AP[4\\PZX54(P^)7CC)7}$" + "EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*").encode()


@pytest.fixture(scope="module")
def stub():
    server, port = create_server(Analyzer(RULES_DIR), "127.0.0.1:0")
    server.start()
    channel = grpc.insecure_channel(f"127.0.0.1:{port}")
    yield engine_pb2_grpc.DetectionEngineStub(channel)
    channel.close()
    server.stop(grace=None)


def stream(data: bytes, chunk_size: int = 16):
    yield engine_pb2.AnalyzeRequest(metadata=engine_pb2.AnalyzeMetadata(sha256="0" * 64, file_size=len(data)))
    for offset in range(0, len(data), chunk_size):
        yield engine_pb2.AnalyzeRequest(chunk=data[offset : offset + chunk_size])


def test_EICAR는_청크로_나눠_보내도_악성으로_판정한다(stub):
    response = stub.Analyze(stream(EICAR))

    assert response.status == engine_pb2.ANALYSIS_STATUS_SUCCESS
    assert response.verdict == engine_pb2.ENGINE_VERDICT_MALICIOUS
    assert [match.rule_name for match in response.matches] == ["EICAR_Test_File"]
    assert response.matches[0].severity == "HIGH"


def test_일반_텍스트는_안전으로_판정한다(stub):
    response = stub.Analyze(stream(b"hello, this is a plain text file"))

    assert response.status == engine_pb2.ANALYSIS_STATUS_SUCCESS
    assert response.verdict == engine_pb2.ENGINE_VERDICT_CLEAN
    assert list(response.matches) == []
