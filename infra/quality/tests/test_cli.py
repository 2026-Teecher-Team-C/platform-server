import pytest

from quality import cli
from quality.records import Evidence, Item, Response, write_evidence, write_items, write_responses

TLSH = "T1" + "E" * 70


def test_운영_대상은_플래그_없이_거부(tmp_path):
    items = tmp_path / "items.csv"
    write_items(items, [])

    with pytest.raises(SystemExit) as raised:
        cli.main(["run", "--http", "https://teecher.example", "--grpc", "teecher.example:443", "--tls",
                  "--items", str(items), "--pass-id", "p1", "--out-dir", str(tmp_path)])

    assert raised.value.code == 2


def test_변종_해제_실패면_종료_코드_1(tmp_path, monkeypatch):
    items = tmp_path / "items-V.csv"
    write_items(items, [Item("V-1", "V", "a" * 64, TLSH, 1, "", "dll", "origin", "a" * 64),
                        Item("V-2", "V", "b" * 64, TLSH, 1, "", "dll", "append-1k", "a" * 64)])
    released = []

    class FakeAdmin:
        def __init__(self, url):
            pass

        def login(self, user, password):
            pass

        def release_blacklist(self, shas):
            released.extend(shas)
            return 0, shas

    monkeypatch.setattr(cli, "Admin", FakeAdmin)
    monkeypatch.setenv("QUALITY_ADMIN_USER", "u")
    monkeypatch.setenv("QUALITY_ADMIN_PASSWORD", "p")

    with pytest.raises(SystemExit) as raised:
        cli.main(["variants", "release", "--http", "http://localhost:8080", "--items", str(items),
                  "--run-id", "r1"])

    assert raised.value.code == 1
    assert released == ["a" * 64]


def test_analyze가_보고서를_만든다(tmp_path):
    items, responses, evidence = tmp_path / "i.csv", tmp_path / "r.csv", tmp_path / "e.csv"
    write_items(items, [Item("M-1", "M", "c" * 64, TLSH, 0, "Mirai", "elf", "", ""),
                        Item("B-1", "B", "d" * 64, TLSH, 9, "7zip/a.dll", "dll", "", "")])
    write_responses(responses, [Response("M-1", "e1", "DECISION_BLOCK", "DECISION_SOURCE_SIMILARITY", "", "", "p1"),
                                Response("B-1", "e2", "DECISION_UNKNOWN", "DECISION_SOURCE_UNSPECIFIED", "", "",
                                         "p1")])
    write_evidence(evidence, [Evidence("e1", 9, "f" * 64, "BLOCK", "tlsh-30-70-v1", "Mirai")])
    out = tmp_path / "report.md"

    cli.main(["analyze", "--items", str(items), "--responses", str(responses), "--evidence", str(evidence),
              "--out", str(out), "--meta", "측정일=2026-11-26"])

    text = out.read_text()
    assert "차단을 30에서 50로 바꾸면" in text
    assert "측정일: 2026-11-26" in text


def test_predict는_불일치가_있으면_종료_코드_1(tmp_path):
    items, responses, evidence = tmp_path / "i.csv", tmp_path / "r.csv", tmp_path / "e.csv"
    write_items(items, [Item("M-1", "M", "c" * 64, TLSH, 0, "", "", "", "")])
    write_responses(responses, [Response("M-1", "e1", "DECISION_UNKNOWN", "DECISION_SOURCE_UNSPECIFIED", "", "",
                                         "p2")])
    write_evidence(evidence, [Evidence("e1", 20, "f" * 64, "WARN", "tlsh-25-60-v1", "")])

    with pytest.raises(SystemExit) as raised:
        cli.main(["predict", "--items", str(items), "--responses", str(responses), "--evidence", str(evidence),
                  "--block", "25"])

    assert raised.value.code == 1
