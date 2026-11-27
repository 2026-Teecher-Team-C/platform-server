import hashlib
import io

import pyzipper
import pytest

from quality import cli
from quality.records import (Evidence, Item, Response, read_yara, write_evidence, write_items, write_responses)

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


EICAR = rb"X5O!P%@AP[4\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*"
YARA_RULES = 'rule eicar_t { meta: severity = "high" strings: $a = "EICAR-STANDARD" condition: $a }\n' \
             'rule off_t { strings: $a = "EICAR" condition: $a }\n'


def _sha(data):
    return hashlib.sha256(data).hexdigest()


def _item(item_id, sha, set_="M"):
    return Item(item_id, set_, sha, TLSH, 1, "", "exe", "", sha)


def _zip(path, data, password=b"infected"):
    with pyzipper.AESZipFile(path, "w", compression=pyzipper.ZIP_DEFLATED, encryption=pyzipper.WZ_AES) as z:
        z.setpassword(password)
        z.writestr("x.bin", data)


def _scan_setup(tmp_path, items):
    rules, disabled, csv_items = tmp_path / "r.yar", tmp_path / "d.txt", tmp_path / "items.csv"
    rules.write_text(YARA_RULES)
    disabled.write_text("off_t\n\n")
    write_items(csv_items, items)
    return ["--rules", str(rules), "--disabled", str(disabled), "--items", str(csv_items)]


def test_fetch_키_없으면_거부(tmp_path, monkeypatch):
    monkeypatch.delenv("ABUSECH_AUTH_KEY", raising=False)
    items = tmp_path / "items.csv"
    write_items(items, [_item("M-1", "a" * 64)])
    with pytest.raises(SystemExit) as e:
        cli.main(["fetch-samples", "--items", str(items), "--dest", str(tmp_path)])
    assert e.value.code == 2


def test_fetch_이미_있으면_건너뜀(tmp_path, monkeypatch, capsys):
    monkeypatch.setenv("ABUSECH_AUTH_KEY", "SECRET-KEY")
    items, dest = tmp_path / "items.csv", tmp_path / "s"
    dest.mkdir()
    write_items(items, [_item("M-1", "a" * 64), _item("M-2", "b" * 64)])
    (dest / ("a" * 64 + ".zip")).write_bytes(b"zip")
    calls, sleeps = [], []

    def fake(sha, key, d):
        calls.append((sha, key))
        return "missing"

    monkeypatch.setattr(cli.bazaar, "fetch_sample", fake)
    monkeypatch.setattr(cli.time, "sleep", sleeps.append)

    cli.main(["fetch-samples", "--items", str(items), "--dest", str(dest), "--sleep", "0.7"])

    out = capsys.readouterr()
    assert calls == [("b" * 64, "SECRET-KEY")]
    assert sleeps == []  # 요청이 한 번뿐이고 건너뜀에는 쉬지 않는다
    assert "skipped=1" in out.out and "missing=1" in out.out and "SECRET-KEY" not in out.out + out.err
    status = dict(line.split(",") for line in (dest / "fetch-status.csv").read_text().split()[1:])
    assert status == {"a" * 64: "ok", "b" * 64: "missing"}


def test_fetch_요청_사이에만_쉼(tmp_path, monkeypatch):
    monkeypatch.setenv("ABUSECH_AUTH_KEY", "K")
    items, dest = tmp_path / "items.csv", tmp_path / "s"
    dest.mkdir()
    write_items(items, [_item("M-1", "a" * 64), _item("M-2", "b" * 64), _item("M-3", "c" * 64)])
    sleeps = []
    monkeypatch.setattr(cli.bazaar, "fetch_sample", lambda sha, key, d: "error")
    monkeypatch.setattr(cli.time, "sleep", sleeps.append)
    cli.main(["fetch-samples", "--items", str(items), "--dest", str(dest), "--sleep", "0.5"])
    assert sleeps == [0.5, 0.5]


def test_zips_스캔_상태별_결과(tmp_path, monkeypatch):
    zips, out = tmp_path / "z", tmp_path / "out.csv"
    zips.mkdir()
    eicar, clean, big, bad = EICAR, b"hello", b"x" * 200, b"y"
    _zip(zips / (_sha(eicar) + ".zip"), eicar)
    _zip(zips / (_sha(clean) + ".zip"), clean)
    _zip(zips / (_sha(big) + ".zip"), big)
    (zips / (_sha(bad) + ".zip")).write_bytes(b"not a zip")
    items = [_item("M-1", _sha(eicar)), _item("M-2", _sha(clean)), _item("M-3", "f" * 64),
             _item("M-4", _sha(big)), _item("M-5", _sha(bad))]
    monkeypatch.setattr(cli, "MAX_FILE_BYTES", 100)

    cli.main(["yara-scan", *_scan_setup(tmp_path, items), "--zips", str(zips), "--out", str(out)])

    rows = read_yara(out)
    assert [(r.item_id, r.status, r.rule_count) for r in rows] == [
        ("M-1", "ok", 1), ("M-2", "ok", 0), ("M-3", "missing", 0), ("M-4", "oversize", 0), ("M-5", "error", 0)]
    assert rows[0].rules == "eicar_t"  # 꺼진 룰은 제외


def test_shard는_겹치지_않고_전부_덮음(tmp_path):
    zips = tmp_path / "z"
    zips.mkdir()
    items = [_item(f"M-{i:02d}", _sha(str(i).encode())) for i in range(10)]
    setup = _scan_setup(tmp_path, items)
    seen = []
    for i in range(3):
        out = tmp_path / f"o{i}.csv"
        cli.main(["yara-scan", *setup, "--zips", str(zips), "--out", str(out), "--shard", f"{i}/3"])
        seen.append([r.item_id for r in read_yara(out)])
    flat = [x for part in seen for x in part]
    assert sorted(flat) == [i.item_id for i in items] and len(flat) == len(set(flat))
    assert seen[0] == ["M-00", "M-03", "M-06", "M-09"]


def test_shard_형식이_틀리면_거부(tmp_path):
    with pytest.raises(SystemExit) as e:
        cli.main(["yara-scan", *_scan_setup(tmp_path, []), "--zips", str(tmp_path), "--out", str(tmp_path / "o.csv"),
                  "--shard", "3/3"])
    assert e.value.code == 2


def test_files_같은_sha256은_item마다_한_줄(tmp_path):
    files, out = tmp_path / "f", tmp_path / "out.csv"
    (files / "a").mkdir(parents=True)
    (files / "b").mkdir()
    (files / "a" / "x.bin").write_bytes(EICAR)
    (files / "b" / "y.bin").write_bytes(EICAR)
    (files / "other.bin").write_bytes(b"not in items")
    items = [_item("B-1", _sha(EICAR), "B"), _item("B-2", _sha(EICAR), "B"), _item("B-3", "e" * 64, "B")]

    cli.main(["yara-scan", *_scan_setup(tmp_path, items), "--files", str(files), "--out", str(out)])

    rows = read_yara(out)
    assert [(r.item_id, r.status, r.rule_count) for r in rows] == [("B-1", "ok", 1), ("B-2", "ok", 1)]  # B-3은 못 찾아 출력 안 함


def test_files_큰_파일은_읽지_않고_oversize(tmp_path, monkeypatch):
    files, out = tmp_path / "f", tmp_path / "out.csv"
    files.mkdir()
    data = EICAR + b"." * 50
    (files / "big.bin").write_bytes(data)
    monkeypatch.setattr(cli, "MAX_FILE_BYTES", 10)
    cli.main(["yara-scan", *_scan_setup(tmp_path, [_item("B-1", _sha(data), "B")]), "--files", str(files), "--out", str(out)])
    assert [(r.item_id, r.status) for r in read_yara(out)] == [("B-1", "oversize")]


def test_files_다시_돌려도_중복_없음(tmp_path):
    files, out = tmp_path / "f", tmp_path / "out.csv"
    files.mkdir()
    (files / "x.bin").write_bytes(EICAR)
    setup = _scan_setup(tmp_path, [_item("B-1", _sha(EICAR), "B")])
    for _ in range(2):
        cli.main(["yara-scan", *setup, "--files", str(files), "--out", str(out)])
    assert [r.item_id for r in read_yara(out)] == ["B-1"]


def test_files_패키지별로_덧붙임(tmp_path):
    out = tmp_path / "out.csv"
    items = [_item("B-1", _sha(b"one"), "B"), _item("B-2", _sha(b"two"), "B")]
    setup = _scan_setup(tmp_path, items)
    for name, data in (("p1", b"one"), ("p2", b"two")):
        d = tmp_path / name
        d.mkdir()
        (d / "f").write_bytes(data)
        cli.main(["yara-scan", *setup, "--files", str(d), "--out", str(out)])
    assert [r.item_id for r in read_yara(out)] == ["B-1", "B-2"]


def _zips_for(tmp_path, datas):
    zips = tmp_path / "z"
    zips.mkdir(exist_ok=True)
    for d in datas:
        _zip(zips / (_sha(d) + ".zip"), d)
    return zips, [_item(f"M-{i}", _sha(d)) for i, d in enumerate(datas)]


def test_zips_이어하기는_이미_있는_item을_건너뜀(tmp_path, monkeypatch):
    datas = [b"a", b"b", b"c"]
    zips, items = _zips_for(tmp_path, datas)
    out = tmp_path / "out.csv"
    setup = _scan_setup(tmp_path, items)
    cli.main(["yara-scan", *setup, "--zips", str(zips), "--out", str(out), "--shard", "0/1"])
    scanned = []
    real = cli.scan_bytes
    monkeypatch.setattr(cli, "scan_bytes", lambda *a: scanned.append(a[1]) or real(*a))
    cli.main(["yara-scan", *setup, "--zips", str(zips), "--out", str(out)])
    assert scanned == []
    assert [r.item_id for r in read_yara(out)] == ["M-0", "M-1", "M-2"]


def test_zips_체크포인트와_inflight_표시로_중단_후_이어짐(tmp_path, monkeypatch):
    datas = [b"a", b"b", b"c", b"d"]
    zips, items = _zips_for(tmp_path, datas)
    out = tmp_path / "out.csv"
    setup = _scan_setup(tmp_path, items)
    monkeypatch.setattr(cli, "CHECKPOINT_EVERY", 2)
    real, calls = cli.scan_bytes, []

    def crash(rules, data, disabled):
        calls.append(data)
        if len(calls) == 3:
            raise KeyboardInterrupt  # 프로세스가 죽는 상황(예외 처리 대상 아님)
        return real(rules, data, disabled)

    monkeypatch.setattr(cli, "scan_bytes", crash)
    with pytest.raises(KeyboardInterrupt):
        cli.main(["yara-scan", *setup, "--zips", str(zips), "--out", str(out)])
    assert [r.item_id for r in read_yara(out)] == ["M-0", "M-1"]  # 중간 체크포인트
    assert (tmp_path / "out.csv.inflight").read_text() == "M-2"

    monkeypatch.setattr(cli, "scan_bytes", real)
    cli.main(["yara-scan", *setup, "--zips", str(zips), "--out", str(out)])
    rows = {r.item_id: r.status for r in read_yara(out)}
    assert rows == {"M-0": "ok", "M-1": "ok", "M-2": "error", "M-3": "ok"}
    assert not (tmp_path / "out.csv.inflight").exists()
    assert not (tmp_path / "out.csv.tmp").exists()


def test_zips_읽기_경로_예외는_그_항목만_error(tmp_path, monkeypatch):
    datas = [b"a", b"b", b"c"]
    zips, items = _zips_for(tmp_path, datas)
    out = tmp_path / "out.csv"
    real = cli.bazaar.read_zip_sample

    def flaky(path, **kw):
        if path.name.startswith(_sha(b"b")):
            raise MemoryError("boom")
        return real(path, **kw)

    monkeypatch.setattr(cli.bazaar, "read_zip_sample", flaky)
    cli.main(["yara-scan", *_scan_setup(tmp_path, items), "--zips", str(zips), "--out", str(out)])
    assert [(r.item_id, r.status) for r in read_yara(out)] == [("M-0", "ok"), ("M-1", "error"), ("M-2", "ok")]


def test_zips_inflight_error는_다음_항목_처리_전에_바로_저장(tmp_path, monkeypatch):
    datas = [b"a", b"b"]
    zips, items = _zips_for(tmp_path, datas)
    out = tmp_path / "out.csv"
    (tmp_path / "out.csv.inflight").write_text("M-0")
    seen = []

    def boom(rules, data, disabled):
        seen.append([(r.item_id, r.status) for r in read_yara(out)])
        raise KeyboardInterrupt

    monkeypatch.setattr(cli, "scan_bytes", boom)
    with pytest.raises(KeyboardInterrupt):
        cli.main(["yara-scan", *_scan_setup(tmp_path, items), "--zips", str(zips), "--out", str(out)])
    assert seen == [[("M-0", "error")]]
