import pytest
import yara

from quality import records, yarascan

EICAR = rb"X5O!P%@AP[4\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*"
RULES = '''
rule eicar_t { meta: severity = "high" strings: $a = "EICAR-STANDARD" condition: $a }
rule off_t { strings: $a = "EICAR" condition: $a }
rule weird_sev { meta: severity = 3 strings: $a = "ANTIVIRUS" condition: $a }
'''


class _RaisingRules:
    def __init__(self, exc):
        self._exc = exc

    def match(self, **kwargs):
        raise self._exc


def test_매칭되면_ok와_룰_목록():
    status, hits, _ = yarascan.scan_bytes(yarascan.compile_rules(RULES), EICAR, frozenset({"off_t"}))
    assert status == "ok"
    assert hits == [("eicar_t", "HIGH"), ("weird_sev", "MEDIUM")]  # 꺼진 룰 제외, 엔진과 같은 심각도 규칙


def test_매칭_없으면_빈_목록():
    assert yarascan.scan_bytes(yarascan.compile_rules(RULES), b"hello", frozenset())[:2] == ("ok", [])


def test_타임아웃은_timeout():
    status, hits, _ = yarascan.scan_bytes(_RaisingRules(yara.TimeoutError("t")), EICAR, frozenset())
    assert (status, hits) == ("timeout", [])


def test_그_밖의_yara_오류는_error():
    status, hits, _ = yarascan.scan_bytes(_RaisingRules(yara.Error("e")), EICAR, frozenset())
    assert (status, hits) == ("error", [])


def test_같은_이름_룰은_한_번만():
    rules = _RaisingRules(None)
    rules.match = lambda **kw: [type("M", (), {"rule": "a", "meta": {}})()] * 2
    assert yarascan.scan_bytes(rules, b"x", frozenset())[1] == [("a", "MEDIUM")]


def test_include는_컴파일_거부():
    with pytest.raises(yara.Error):
        yarascan.compile_rules('include "/etc/passwd"\nrule a { condition: true }')


def test_결과_레코드_왕복(tmp_path):
    r = yarascan.to_result("M-1", "a" * 64, "ok", [("b", "LOW"), ("a", "CRITICAL")], 12)
    assert (r.rules, r.rule_count, r.max_severity) == ("a;b", 2, "CRITICAL")
    records.write_yara(tmp_path / "y.csv", [r])
    assert records.read_yara(tmp_path / "y.csv") == [r]


def test_매칭_없는_결과는_max_severity_빈값(tmp_path):
    r = yarascan.to_result("M-2", "b" * 64, "missing", [], 0)
    assert (r.rules, r.rule_count, r.max_severity) == ("", 0, "")
    records.write_yara(tmp_path / "y.csv", [r])
    assert records.read_yara(tmp_path / "y.csv") == [r]
