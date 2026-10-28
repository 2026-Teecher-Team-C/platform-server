from pathlib import Path

import grpc
import pytest

from detection_engine.analyzer import Analyzer
from detection_engine.server import create_server
from teecher.engine.v1 import engine_pb2, engine_pb2_grpc

RULES_DIR = Path(__file__).resolve().parents[1] / "rules"
EICAR_MARKER = b"EICAR-STANDARD-ANTIVIRUS-TEST-FILE!"


@pytest.fixture
def stub(tmp_path):
    # 적재 상태가 테스트 사이로 새지 않도록 테스트마다 서버를 띄운다.
    server, port = create_server(Analyzer(RULES_DIR), "127.0.0.1:0", tmp_path)
    server.start()
    channel = grpc.insecure_channel(f"127.0.0.1:{port}")
    yield engine_pb2_grpc.DetectionEngineStub(channel)
    channel.close()
    server.stop(grace=None)


def stream(data: bytes, ruleset_key: str = ""):
    yield engine_pb2.AnalyzeRequest(
        metadata=engine_pb2.AnalyzeMetadata(sha256="0" * 64, file_size=len(data), ruleset_key=ruleset_key)
    )
    yield engine_pb2.AnalyzeRequest(chunk=data)


def load(stub, version, key, source, disabled=()):
    return stub.LoadRuleset(
        engine_pb2.LoadRulesetRequest(
            rulesets_version=version, ruleset_key=key, source=source, disabled_rule_names=list(disabled)
        )
    )


def test_컴파일_검증은_룰_이름과_심각도를_돌려준다(stub):
    source = 'rule A { meta: severity = "critical" condition: true }\nrule B { condition: false }'

    response = stub.CompileRules(engine_pb2.CompileRulesRequest(source=source))

    assert response.ok
    assert [(rule.name, rule.severity) for rule in response.rules] == [("A", "CRITICAL"), ("B", "MEDIUM")]


def test_문법_오류는_ok_false와_메시지를_준다(stub):
    response = stub.CompileRules(engine_pb2.CompileRulesRequest(source="rule { }"))

    assert not response.ok
    assert response.error_message != ""


def test_include는_거부한다(stub):
    response = stub.CompileRules(
        engine_pb2.CompileRulesRequest(source='include "/etc/passwd"\nrule A { condition: true }')
    )

    assert not response.ok


def test_1MiB를_넘는_원문은_거부한다(stub):
    response = stub.CompileRules(engine_pb2.CompileRulesRequest(source="//" + "x" * (1024 * 1024)))

    assert not response.ok


def test_1MiB를_넘는_원문은_적재도_거부한다(stub):
    with pytest.raises(grpc.RpcError) as error:
        load(stub, 1, "1:k", "//" + "x" * (1024 * 1024))

    assert error.value.code() == grpc.StatusCode.INVALID_ARGUMENT


def test_컴파일_실패한_원문은_적재하지_않는다(stub):
    with pytest.raises(grpc.RpcError) as error:
        load(stub, 1, "1:k", "rule { }")

    assert error.value.code() == grpc.StatusCode.INVALID_ARGUMENT
    assert stub.Analyze(stream(EICAR_MARKER)).rulesets_version == 0


def test_키가_다르면_FAILED_PRECONDITION(stub):
    with pytest.raises(grpc.RpcError) as error:
        stub.Analyze(stream(EICAR_MARKER, ruleset_key="1:abc"))

    assert error.value.code() == grpc.StatusCode.FAILED_PRECONDITION


def test_적재_후에는_새_룰로_판정하고_버전을_채운다(stub):
    load(stub, 7, "7:k", 'rule Only { strings: $a = "ONLY-MARK" condition: $a }')

    response = stub.Analyze(stream(b"ONLY-MARK", ruleset_key="7:k"))
    assert response.verdict == engine_pb2.ENGINE_VERDICT_MALICIOUS
    assert response.rulesets_version == 7

    assert stub.Analyze(stream(EICAR_MARKER, ruleset_key="7:k")).verdict == engine_pb2.ENGINE_VERDICT_CLEAN


def test_꺼진_룰의_매치는_판정에서_뺀다(stub):
    source = 'rule On { strings: $a = "AAA" condition: $a }\nrule Off { strings: $b = "BBB" condition: $b }'
    load(stub, 2, "2:k", source, disabled=["Off"])

    response = stub.Analyze(stream(b"BBB", ruleset_key="2:k"))

    assert response.verdict == engine_pb2.ENGINE_VERDICT_CLEAN
    assert list(response.matches) == []
    assert stub.Analyze(stream(b"AAA", ruleset_key="2:k")).verdict == engine_pb2.ENGINE_VERDICT_MALICIOUS


def test_키가_비어_있으면_검사하지_않는다(stub):
    load(stub, 3, "3:k", 'rule Only { strings: $a = "ONLY-MARK" condition: $a }')

    response = stub.Analyze(stream(b"ONLY-MARK", ruleset_key=""))

    assert response.verdict == engine_pb2.ENGINE_VERDICT_MALICIOUS
