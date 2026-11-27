import pytest

from quality.analyze import join
from quality.records import Evidence, Item, Response, YaraResult
from quality.yara_report import Combined, combine, render

TLSH = "T1" + "D" * 70
SIM = "DECISION_SOURCE_SIMILARITY"


def build(specs):
    """specs: (set, distance|None, yara_status|None, rules, family, file_type, source, error)"""
    items, responses, evidence, yara = [], [], [], []
    for n, s in enumerate(specs):
        set_name, distance, status, rules = s[:4]
        family = s[4] if len(s) > 4 else ""
        file_type = s[5] if len(s) > 5 else "exe"
        source = s[6] if len(s) > 6 else "DECISION_SOURCE_UNSPECIFIED"
        error = s[7] if len(s) > 7 else ""
        item_id = f"{set_name}-{n}"
        items.append(Item(item_id, set_name, format(n, "064x"), TLSH, 100, family, file_type, "", ""))
        responses.append(Response(item_id, f"e{n}", "" if error else "DECISION_UNKNOWN", "" if error else source, "",
                                  error, "p1"))
        if distance is not None:
            evidence.append(Evidence(f"e{n}", distance, "f" * 64, "BLOCK" if distance <= 30 else "WARN", "v", family))
        if status is not None:
            names = [r for r in rules.split(";") if r]
            yara.append(YaraResult(item_id, format(n, "064x"), status, len(names), rules, "high" if names else "", 10 * (n + 1)))
    return join(items, responses, evidence), yara


def run(specs, meta=None):
    rows, yara = build(specs)
    combined = combine(rows, yara)
    return combined, render(combined, meta or {})


def test_YARA_차단은_룰_매칭만():
    combined, md = run([("M", 50, "ok", "R1"), ("M", 50, "timeout", ""), ("M", 50, "oversize", ""),
                        ("M", 50, "error", "")])

    assert [c.yara for c in combined] == ["HIT", "TIMEOUT", "OVERSIZE", "ERROR"]
    assert "| YARA 차단(룰 매칭) | 1 | 25.0% |" in md
    assert "| YARA timeout | 1 | 25.0% |" in md
    assert "| YARA oversize | 1 | 25.0% |" in md
    assert "| YARA error | 1 | 25.0% |" in md
    assert "fail-close" in md


def test_missing은_분모에서_빠지고_TLSH도_다시_계산():
    combined, md = run([("M", 10, None, ""), ("M", 50, "ok", ""), ("M", 80, "missing", "")])

    assert [c.yara for c in combined] == ["MISSING", "MISS", "MISSING"]
    assert "지난 발표 기준 n=3" in md
    assert "TLSH 차단 33.3%" in md  # 지난 발표 기준(전체 3개 중 1개)
    assert "| TLSH 차단 | 0 | 0.0% |" in md  # 받은 1개 기준
    assert "| 받은 샘플 | 1 | 100.0% |" in md


def test_합친_차단은_TLSH_또는_YARA():
    _, md = run([("M", 10, "ok", ""), ("M", 50, "ok", "R"), ("M", None, "ok", "R"), ("M", None, "ok", "")])

    assert "| TLSH 단독 | 1 | 25.0% |" in md
    assert "| YARA 단독 | 2 | 50.0% |" in md
    assert "| TLSH 또는 YARA | 3 | 75.0% |" in md
    assert "| 둘 다 못 잡음 | 1 | 25.0% |" in md


def test_TLSH_미차단_중_YARA_보완을_경고_통과로_나눔():
    _, md = run([("M", 10, "ok", "R"), ("M", 50, "ok", "R"), ("M", 50, "ok", ""), ("M", None, "ok", "R"),
                 ("M", None, "ok", ""), ("M", 90, "ok", "")])

    assert "| TLSH 미차단 전체 | 5 | 2 | 40.0% |" in md
    assert "| 경고 | 2 | 1 | 50.0% |" in md
    assert "| 통과 | 3 | 1 | 33.3% |" in md


def test_목록판정과_오류는_제외():
    combined, _ = run([("M", 10, "ok", ""), ("M", None, "ok", "", "", "exe", "DECISION_SOURCE_BLACKLIST"),
                       ("M", None, "ok", "", "", "exe", "DECISION_SOURCE_UNSPECIFIED", "UNAVAILABLE")])

    assert [c.item_id for c in combined] == ["M-0"]


def test_B_오탐률과_상위_룰():
    _, md = run([("B", None, "ok", "RuleA;RuleB"), ("B", None, "ok", ""), ("B", None, "ok", "")])

    assert "| YARA 매칭(오탐) | 1 | 33.3% |" in md
    assert "| RuleA | 1 |" in md


def test_패밀리와_파일형식_행():
    _, md = run([("M", 10, "ok", "", "Mirai", "elf"), ("M", None, "ok", "R", "", "exe"),
                 ("M", None, "ok", "", "Mirai", "elf")])

    assert "| Mirai | 2 | 50.0% | 50.0% | 50.0% |" in md
    assert "| (라벨 없음) | 1 | 0.0% | 100.0% | 0.0% |" in md
    assert "| elf | 2 |" in md


def test_검사_시간은_ok만():
    _, md = run([("M", 50, "ok", ""), ("M", 50, "ok", ""), ("M", 50, "timeout", "")])

    assert "운영 엔진 사양 근사치(1 CPU / 1GiB)" in md
    assert "| p50 | 10 |" in md
    assert "| 최대 | 20 |" in md
    assert "| timeout | 1 |" in md


def test_CLI_중복_item_id는_코드_2(tmp_path):
    from quality.cli import main
    from quality.records import write_items, write_responses, write_evidence, write_yara
    rows, yara = build([("M", 10, "ok", "")])
    write_items(tmp_path / "i.csv", [Item("M-0", "M", format(0, "064x"), TLSH, 100, "", "exe", "", "")])
    write_responses(tmp_path / "r.csv", [Response("M-0", "e0", "DECISION_UNKNOWN", "", "", "", "p1")])
    write_evidence(tmp_path / "e.csv", [Evidence("e0", 10, "f" * 64, "BLOCK", "v", "")])
    write_yara(tmp_path / "y1.csv", yara)
    write_yara(tmp_path / "y2.csv", yara)
    argv = ["yara-report", "--items", str(tmp_path / "i.csv"), "--responses", str(tmp_path / "r.csv"),
            "--evidence", str(tmp_path / "e.csv"), "--out", str(tmp_path / "o.md")]

    with pytest.raises(SystemExit) as e:
        main(argv + ["--yara", str(tmp_path / "y1.csv"), str(tmp_path / "y2.csv")])
    assert e.value.code == 2

    main(argv + ["--yara", str(tmp_path / "y1.csv")])
    assert "TLSH" in (tmp_path / "o.md").read_text()


def test_표3은_샘플_없는_패밀리도_0행으로_남김():
    _, md = run([("M", 10, "ok", "", "Mirai", "elf")])

    assert "| AgentTesla | 0 | - | - | - |" in md
    assert "| (라벨 없음) | 0 | - | - | - |" in md


def test_표4는_B_결과_없음_개수를_밝힘():
    _, md = run([("B", 99, "ok", ""), ("B", 99, "ok", "R1"), ("B", 99, None, ""), ("B", 99, "missing", "")])
    assert "- B 대상 4, 결과 없음 2 (분모에서 제외)" in md
    assert "| 받은 샘플 | 2 | 100.0% |" in md
