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


V_ITEMS = [Item("V-1", "V", "a" * 64, TLSH, 1, "", "dll", "origin", "a" * 64),
           Item("V-2", "V", "b" * 64, TLSH, 1, "", "dll", "append-1k", "a" * 64),
           Item("V-3", "V", "c" * 64, TLSH, 1, "", "dll", "origin", "c" * 64)]


class FakeAdmin:
    added = 2
    released = []
    fail = set()

    def __init__(self, url):
        pass

    def login(self, user, password):
        pass

    def import_blacklist(self, items, label):
        return {"received": len(items), "added": FakeAdmin.added}

    def release_blacklist(self, shas):
        FakeAdmin.released.extend(shas)
        failed = [s for s in shas if s in FakeAdmin.fail]
        return len(shas) - len(failed), failed


@pytest.fixture
def fake_admin(monkeypatch, tmp_path):
    FakeAdmin.added, FakeAdmin.released, FakeAdmin.fail = 2, [], set()
    monkeypatch.setattr(cli, "Admin", FakeAdmin)
    items = tmp_path / "items-V.csv"
    write_items(items, V_ITEMS)
    return items


def variants(action, items, tmp_path):
    cli.main(["variants", action, "--http", "http://localhost:8080", "--items", str(items), "--run-id", "r1",
              "--out-dir", str(tmp_path)])


def test_변종_등록이_전부_추가되지_않으면_멈추고_해제_목록을_안_남긴다(fake_admin, tmp_path):
    FakeAdmin.added = 1

    with pytest.raises(SystemExit) as raised:
        variants("register", fake_admin, tmp_path)

    assert raised.value.code == 1
    assert not (tmp_path / "registered-r1.txt").exists()


def test_변종_해제는_등록에_성공한_목록만_해제한다(fake_admin, tmp_path):
    variants("register", fake_admin, tmp_path)
    assert (tmp_path / "registered-r1.txt").read_text().split() == ["a" * 64, "c" * 64]

    variants("release", fake_admin, tmp_path)

    assert FakeAdmin.released == ["a" * 64, "c" * 64]


def test_등록_목록_없이_해제하면_아무것도_안_건드린다(fake_admin, tmp_path):
    with pytest.raises(SystemExit) as raised:
        variants("release", fake_admin, tmp_path)

    assert raised.value.code == 1
    assert FakeAdmin.released == []


def test_변종_해제_실패면_종료_코드_1(fake_admin, tmp_path):
    variants("register", fake_admin, tmp_path)
    FakeAdmin.fail = {"c" * 64}

    with pytest.raises(SystemExit) as raised:
        variants("release", fake_admin, tmp_path)

    assert raised.value.code == 1


def test_같은_pass_id로_다시_돌리면_거부(tmp_path):
    items = tmp_path / "items.csv"
    write_items(items, [])
    (tmp_path / "agent-p1.txt").write_text("old-agent\n")

    with pytest.raises(SystemExit) as raised:
        cli.main(["run", "--http", "http://localhost:8080", "--grpc", "localhost:9090", "--items", str(items),
                  "--pass-id", "p1", "--out-dir", str(tmp_path)])

    assert raised.value.code == 2
    assert (tmp_path / "agent-p1.txt").read_text() == "old-agent\n"


def test_analyze가_보고서를_만든다(tmp_path):
    items, responses, evidence = tmp_path / "i.csv", tmp_path / "r.csv", tmp_path / "e.csv"
    write_items(items, [Item("M-1", "M", "c" * 64, TLSH, 0, "Mirai", "elf", "", ""),
                        Item("B-1", "B", "d" * 64, TLSH, 9, "7zip/a.dll", "dll", "", "")])
    write_responses(responses, [Response("M-1", "e1", "DECISION_BLOCK", "DECISION_SOURCE_SIMILARITY", "", "", "p1"),
                                Response("B-1", "e2", "DECISION_UNKNOWN", "DECISION_SOURCE_UNSPECIFIED", "", "",
                                         "p1")])
    write_evidence(evidence, [Evidence("e1", 9, "f" * 64, "BLOCK", "tlsh-30-70-v1", "MalwareBazaar: Mirai"),
                              Evidence("e2", None, "", "", "", "")])
    out = tmp_path / "report.md"

    cli.main(["analyze", "--items", str(items), "--responses", str(responses), "--evidence", str(evidence),
              "--out", str(out), "--meta", "측정일=2026-11-26"])

    text = out.read_text()
    assert "차단을 30에서 50로 바꾸면" in text
    assert "측정일: 2026-11-26" in text


def test_predict는_불일치가_있으면_종료_코드_1(tmp_path):
    items, r1, r2, e1, e2 = (tmp_path / n for n in ("i.csv", "r1.csv", "r2.csv", "e1.csv", "e2.csv"))
    write_items(items, [Item("M-1", "M", "c" * 64, TLSH, 0, "", "", "", "")])
    write_responses(r1, [Response("M-1", "e1", "DECISION_BLOCK", "DECISION_SOURCE_SIMILARITY", "", "", "p1")])
    write_responses(r2, [Response("M-1", "e2", "DECISION_UNKNOWN", "DECISION_SOURCE_UNSPECIFIED", "", "", "p2")])
    write_evidence(e1, [Evidence("e1", 20, "f" * 64, "BLOCK", "tlsh-30-70-v1", "")])
    write_evidence(e2, [Evidence("e2", 20, "f" * 64, "WARN", "tlsh-15-70-v1", "")])
    args = ["predict", "--items", str(items), "--base-responses", str(r1), "--base-evidence", str(e1),
            "--responses", str(r2), "--evidence", str(e2), "--warn", "70"]

    with pytest.raises(SystemExit) as raised:
        cli.main(args + ["--block", "25"])
    assert raised.value.code == 1

    cli.main(args + ["--block", "15"])


def test_analyze는_근거가_빠진_요청이_있으면_멈춘다(tmp_path):
    items, responses, evidence = tmp_path / "i.csv", tmp_path / "r.csv", tmp_path / "e.csv"
    write_items(items, [Item("M-1", "M", "c" * 64, TLSH, 0, "", "", "", "")])
    write_responses(responses, [Response("M-1", "e9", "DECISION_UNKNOWN", "DECISION_SOURCE_UNSPECIFIED", "", "",
                                         "p2")])
    write_evidence(evidence, [Evidence("e1", 20, "f" * 64, "WARN", "tlsh-30-70-v1", "")])

    with pytest.raises(SystemExit) as raised:
        cli.main(["analyze", "--items", str(items), "--responses", str(responses), "--evidence", str(evidence),
                  "--out", str(tmp_path / "r.md")])

    assert raised.value.code == 1
