import sys
from pathlib import Path

import grpc
import pytest

from detection_engine import server as engine_server
from detection_engine.analyzer import Analyzer
from detection_engine.server import create_server
from teecher.engine.v1 import engine_pb2, engine_pb2_grpc

RULES_DIR = Path(__file__).resolve().parents[1] / "rules"

# 로컬 백신이 테스트 파일을 격리하지 않도록 EICAR 문자열을 조립해서 쓴다.
EICAR = ("X5O!P%@AP[4\\PZX54(P^)7CC)7}$" + "EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*").encode()
# YARA 룰이 보는 부분만. 엔진이 입력을 디스크에 쓰므로, 완전한 EICAR를 쓰는 테스트는 로컬 백신이 임시 파일을
# 격리해서 실패할 수 있다(Windows Defender). 엔진 동작만 보는 테스트는 이 조각을 쓴다.
EICAR_MARKER = b"EICAR-STANDARD-ANTIVIRUS-TEST-FILE!"
# 데모용 가짜 악성 샘플의 표식(rules/teecher_demo.yar). 실행 코드가 없는 무해한 문자열이다.
DEMO_MARKER = b"TEECHER-DEMO-MALWARE"
DEMO_C2 = b"evil-c2.teecher.invalid"


@pytest.fixture(scope="module")
def spool_dir(tmp_path_factory):
    return tmp_path_factory.mktemp("engine-spool")


@pytest.fixture(scope="module")
def stub(spool_dir):
    server, port = create_server(Analyzer(RULES_DIR), "127.0.0.1:0", spool_dir)
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


def test_EICAR가_청크_경계에_걸쳐도_찾는다(stub):
    # 청크 크기 7은 EICAR 문자열 한가운데를 가른다. 파일로 합친 뒤 매칭하므로 경계는 의미가 없어야 한다.
    response = stub.Analyze(stream(b"padding-" + EICAR_MARKER + b"-padding", chunk_size=7))

    assert response.verdict == engine_pb2.ENGINE_VERDICT_MALICIOUS


def test_데모_샘플은_표식과_C2_주소가_모두_있으면_악성으로_판정한다(stub):
    sample = b"MZ fake header\n" + DEMO_MARKER + b"\nconnect " + DEMO_C2 + b"\n"

    response = stub.Analyze(stream(sample))

    assert response.verdict == engine_pb2.ENGINE_VERDICT_MALICIOUS
    assert [match.rule_name for match in response.matches] == ["Teecher_Demo_Fake_Malware"]
    assert response.matches[0].severity == "HIGH"


@pytest.mark.parametrize("data", [DEMO_MARKER, DEMO_C2], ids=["표식만", "C2_주소만"])
def test_데모_표식이_하나만_있으면_안전으로_판정한다(stub, data):
    # 문서나 로그가 표식 하나를 언급하는 정도로는 막지 않는다.
    response = stub.Analyze(stream(b"note: " + data))

    assert response.verdict == engine_pb2.ENGINE_VERDICT_CLEAN


def test_분석이_끝나면_임시_파일이_남지_않는다(stub, spool_dir):
    stub.Analyze(stream(EICAR_MARKER))
    stub.Analyze(stream(b"clean"))

    assert list(spool_dir.iterdir()) == []


def test_상한을_넘으면_UNSUPPORTED이고_임시_파일이_남지_않는다(stub, spool_dir, monkeypatch):
    monkeypatch.setattr(engine_server, "MAX_FILE_BYTES", 100)

    response = stub.Analyze(stream(b"x" * 1000, chunk_size=64))

    assert response.status == engine_pb2.ANALYSIS_STATUS_UNSUPPORTED
    assert response.verdict == engine_pb2.ENGINE_VERDICT_UNKNOWN
    assert list(spool_dir.iterdir()) == []


@pytest.mark.skipif(sys.platform == "win32", reason="Windows는 POSIX 파일 권한 비트를 반영하지 않는다")
def test_임시_파일은_소유자만_읽고_쓸_수_있고_실행_비트가_없다(spool_dir):
    # 분석 도중의 파일 권한을 본다: 분석기를 가로채 그 시점의 모드를 기록한다.
    modes = []

    class SpyAnalyzer(Analyzer):
        def analyze_file(self, path):
            modes.append(path.stat().st_mode & 0o777)
            return super().analyze_file(path)

    server, port = create_server(SpyAnalyzer(RULES_DIR), "127.0.0.1:0", spool_dir)
    server.start()
    try:
        with grpc.insecure_channel(f"127.0.0.1:{port}") as channel:
            engine_pb2_grpc.DetectionEngineStub(channel).Analyze(stream(b"clean"))
    finally:
        server.stop(grace=None)

    assert modes == [0o600]
